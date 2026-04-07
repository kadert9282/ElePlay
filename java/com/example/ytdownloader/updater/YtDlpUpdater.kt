package com.example.ytdownloader.updater

import android.content.Context
import com.example.ytdownloader.runtime.RuntimePaths
import com.example.ytdownloader.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class YtDlpUpdater(
    private val context: Context,
    private val paths: RuntimePaths,
    private val prefs: YtDlpUpdatePrefs,
    private val logger: FileLogger
) {

    companion object {
        private const val PYPI_API_URL = "https://pypi.org/pypi/yt-dlp/json"
        private const val PYPI_ALL_VERSIONS_URL = "https://pypi.org/pypi/yt-dlp/json"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 60_000
    }

    // ─── Версии ───────────────────────────────────────────────────────────────

    fun getBuiltinVersion(): String {
        return try {
            val versionFile = File(paths.sitePackages, "yt_dlp/version.py")
            parseVersionFromFile(versionFile) ?: "unknown"
        } catch (e: Exception) {
            logger.log("getBuiltinVersion error: ${e.message}")
            "unknown"
        }
    }

    fun getUpdatedVersion(): String? {
        return try {
            val versionFile = File(paths.sitePackagesUpdate, "yt_dlp/version.py")
            if (!versionFile.exists()) return null
            parseVersionFromFile(versionFile)
        } catch (e: Exception) {
            logger.log("getUpdatedVersion error: ${e.message}")
            null
        }
    }

    fun isUpdateInstalled(): Boolean {
        return File(paths.sitePackagesUpdate, "yt_dlp/__init__.py").exists()
    }

    // ─── Получение списка всех версий ─────────────────────────────────────────

    suspend fun fetchAllReleases(): Result<List<YtDlpRelease>> = withContext(Dispatchers.IO) {
        try {
            logger.log("Fetching all yt-dlp releases from PyPI...")
            val pypiJson = fetchPypiInfo()

            val latestVersion = pypiJson
                .getJSONObject("info")
                .getString("version")
                .trim()

            val releasesJson = pypiJson.getJSONObject("releases")

            // Собираем все версии с датой загрузки
            data class ReleaseWithDate(
                val version: String,
                val uploadTime: String,
                val downloadUrl: String
            )

            val releasesWithDates = mutableListOf<ReleaseWithDate>()

            releasesJson.keys().asSequence().forEach { version ->
                val filesArray = releasesJson.getJSONArray(version)
                var whlUrl: String? = null
                var uploadTime: String? = null

                // Ищем py3-none-any.whl
                for (i in 0 until filesArray.length()) {
                    val file = filesArray.getJSONObject(i)
                    val filename = file.getString("filename")
                    if (filename.startsWith("yt_dlp-") &&
                        filename.endsWith("-py3-none-any.whl")
                    ) {
                        whlUrl = file.getString("url")
                        uploadTime = file.optString("upload_time", null)
                        break
                    }
                }

                // Fallback — любой whl
                if (whlUrl == null) {
                    for (i in 0 until filesArray.length()) {
                        val file = filesArray.getJSONObject(i)
                        val filename = file.getString("filename")
                        if (filename.startsWith("yt_dlp-") && filename.endsWith(".whl")) {
                            whlUrl = file.getString("url")
                            uploadTime = file.optString("upload_time", null)
                            break
                        }
                    }
                }

                if (whlUrl != null && uploadTime != null) {
                    releasesWithDates.add(
                        ReleaseWithDate(
                            version = version,
                            uploadTime = uploadTime,
                            downloadUrl = whlUrl
                        )
                    )
                }
            }

            // Сортируем по дате: новые сверху
            val sorted = releasesWithDates.sortedByDescending { it.uploadTime }

            val releases = sorted.map { release ->
                YtDlpRelease(
                    version = release.version,
                    downloadUrl = release.downloadUrl,
                    isLatest = release.version == latestVersion
                )
            }

            logger.log("Fetched ${releases.size} releases, latest: $latestVersion")
            Result.success(releases)

        } catch (e: Exception) {
            logger.log("fetchAllReleases error: ${e.message}")
            Result.failure(e)
        }
    }

    // ─── Проверка обновления ──────────────────────────────────────────────────

    suspend fun checkForUpdate(): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        try {
            logger.log("Checking for yt-dlp update via PyPI...")

            val pypiJson = fetchPypiInfo()
            val latestVersion = pypiJson
                .getJSONObject("info")
                .getString("version")
                .trim()

            val currentVersion = when {
                prefs.isUpdatedMode() && isUpdateInstalled() ->
                    getUpdatedVersion() ?: getBuiltinVersion()
                else ->
                    getBuiltinVersion()
            }

            logger.log("Current: $currentVersion | Latest: $latestVersion")

            val downloadUrl = findWhlUrl(pypiJson, latestVersion)
                ?: return@withContext Result.failure(
                    Exception("No .whl found on PyPI for version $latestVersion")
                )

            val hasUpdate = latestVersion != currentVersion

            logger.log("Has update: $hasUpdate | URL: $downloadUrl")

            Result.success(
                UpdateCheckResult(
                    latestVersion = latestVersion,
                    currentVersion = currentVersion,
                    downloadUrl = downloadUrl,
                    hasUpdate = hasUpdate
                )
            )
        } catch (e: Exception) {
            logger.log("checkForUpdate error: ${e.message}")
            Result.failure(e)
        }
    }

    // ─── Скачивание и установка ───────────────────────────────────────────────

    suspend fun downloadAndInstall(
        downloadUrl: String,
        version: String,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tempFile = File(paths.tempDir, "yt_dlp_update.whl")

        try {
            logger.log("Downloading yt-dlp $version from PyPI: $downloadUrl")

            tempFile.parentFile?.mkdirs()

            downloadFile(downloadUrl, tempFile, onProgress)
            logger.log("Download complete: ${tempFile.length()} bytes")

            onProgress(100)

            val updateDir = File(paths.sitePackagesUpdate)
            if (updateDir.exists()) {
                logger.log("Removing old update dir...")
                updateDir.deleteRecursively()
            }
            updateDir.mkdirs()

            logger.log("Extracting whl to ${updateDir.absolutePath}")
            extractWhl(tempFile, updateDir)

            val ytDlpInit = File(updateDir, "yt_dlp/__init__.py")
            if (!ytDlpInit.exists()) {
                updateDir.deleteRecursively()
                return@withContext Result.failure(
                    Exception("yt_dlp/__init__.py not found after extraction")
                )
            }

            logger.log("version.py exists: ${File(updateDir, "yt_dlp/version.py").exists()}")

            prefs.setInstalledVersion(version)
            logger.log("yt-dlp $version installed successfully")

            Result.success(Unit)

        } catch (e: Exception) {
            logger.log("downloadAndInstall failed: ${e.message}")
            logger.log(e.stackTraceToString())
            runCatching { File(paths.sitePackagesUpdate).deleteRecursively() }
            runCatching { prefs.clearInstalledVersion() }
            Result.failure(e)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    // ─── Удаление обновления ──────────────────────────────────────────────────

    fun removeUpdate() {
        try {
            File(paths.sitePackagesUpdate).deleteRecursively()
            prefs.clearInstalledVersion()
            logger.log("Updated yt-dlp removed, reverted to builtin")
        } catch (e: Exception) {
            logger.log("removeUpdate error: ${e.message}")
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun fetchPypiInfo(): JSONObject {
        val connection = URL(PYPI_API_URL).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ElePlay-Android-App")
        }

        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw Exception("PyPI API returned HTTP $code")
        }

        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(body)
    }

    private fun findWhlUrl(pypiJson: JSONObject, version: String): String? {
        val urls = pypiJson.optJSONArray("urls")
        if (urls != null) {
            for (i in 0 until urls.length()) {
                val file = urls.getJSONObject(i)
                val filename = file.getString("filename")
                val packageType = file.optString("packagetype", "")

                if (packageType == "bdist_wheel" &&
                    filename.startsWith("yt_dlp-") &&
                    filename.endsWith("-py3-none-any.whl")
                ) {
                    val url = file.getString("url")
                    logger.log("Found whl on PyPI: $filename")
                    return url
                }
            }

            for (i in 0 until urls.length()) {
                val file = urls.getJSONObject(i)
                val filename = file.getString("filename")
                if (filename.startsWith("yt_dlp-") && filename.endsWith(".whl")) {
                    val url = file.getString("url")
                    logger.log("Found whl on PyPI (fallback): $filename")
                    return url
                }
            }
        }

        val directUrl = "https://files.pythonhosted.org/packages/py3/y/yt_dlp/" +
                "yt_dlp-${version}-py3-none-any.whl"
        logger.log("Using direct PyPI URL: $directUrl")
        return directUrl
    }

    private fun downloadFile(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", "ElePlay-Android-App")
            instanceFollowRedirects = true
        }

        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L
        var lastReportedPercent = -1

        connection.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    if (totalBytes > 0) {
                        val percent = (downloadedBytes * 100L / totalBytes)
                            .toInt()
                            .coerceIn(0, 99)
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(percent)
                        }
                    }
                }
                output.flush()
            }
        }
    }

    private fun extractWhl(whlFile: File, destDir: File) {
        ZipInputStream(whlFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.name.contains(".dist-info/")) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            val buffer = ByteArray(16384)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                            fos.flush()
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun parseVersionFromFile(file: File): String? {
        if (!file.exists()) return null
        return try {
            val content = file.readText()
            val regex = Regex("""__version__\s*=\s*['"]([^'"]+)['"]""")
            regex.find(content)?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.log("parseVersionFromFile error: ${e.message}")
            null
        }
    }
}

data class UpdateCheckResult(
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String,
    val hasUpdate: Boolean
)