package com.example.ytdownloader.engine

import android.content.Context
import com.example.ytdownloader.model.DownloadProgress
import com.example.ytdownloader.model.DownloadResult
import com.example.ytdownloader.model.FormatItem
import com.example.ytdownloader.model.PlaylistInfo
import com.example.ytdownloader.model.PlaylistItem
import com.example.ytdownloader.model.VideoInfo
import com.example.ytdownloader.runtime.PythonProcessRunner
import com.example.ytdownloader.runtime.RuntimeManager
import com.example.ytdownloader.runtime.RuntimePaths
import com.example.ytdownloader.util.FileLogger
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

class PythonYtDlpEngine(
    private val context: Context,
    private val logger: FileLogger
) : VideoEngine {

    private val runtimeManager = RuntimeManager(context, logger)
    private val paths: RuntimePaths get() = runtimeManager.paths
    private val processRunner by lazy { PythonProcessRunner(paths, logger) }

    @Volatile
    private var lastResult: DownloadResult? = null

    @Volatile
    private var lastDownloadedFile: String? = null

    @Volatile
    private var currentFfmpegProcess: Process? = null

    // ─── Public helpers для updater ───────────────────────────────────────────

    fun getRuntimePaths(): RuntimePaths = runtimeManager.paths

    /**
     * Устанавливает режим yt-dlp для всех последующих запросов.
     * Вызывается из MainViewModel когда пользователь меняет режим.
     */
    fun setUseUpdatedYtDlp(useUpdated: Boolean) {
        processRunner.useUpdatedYtDlp = useUpdated
        logger.log("Engine: useUpdatedYtDlp=$useUpdated")
    }

    // ─── VideoEngine impl ─────────────────────────────────────────────────────

    override suspend fun isRuntimeReady(): Boolean = withContext(Dispatchers.IO) {
        runtimeManager.isRuntimeReady()
    }

    override fun initializeRuntime(): Flow<String> = flow {
        emit("Checking runtime...")

        if (runtimeManager.isRuntimeReady()) {
            emit("Runtime already initialized")
            val problems = paths.diagnose()
            if (problems.isNotEmpty()) {
                problems.forEach { emit("WARNING: $it") }
                emit("Rebuilding runtime...")
                runtimeManager.clearRuntime()
            } else {
                emit("All files verified OK")
                return@flow
            }
        } else {
            emit("Runtime rebuild required")
            runtimeManager.clearRuntime()
        }

        emit("Creating directories...")
        runtimeManager.extractRuntime {}

        emit("Extracting binaries...")
        runtimeManager.extractBinaries()
        emit("Binaries extracted")

        emit("Extracting Python stdlib...")
        runtimeManager.extractPythonStdlib()
        emit("Stdlib extracted")

        emit("Extracting yt_dlp...")
        runtimeManager.extractSitePackages()
        emit("yt_dlp extracted")

        emit("Extracting scripts...")
        runtimeManager.extractScripts()
        emit("Scripts extracted")

        emit("Setting permissions...")
        runtimeManager.setPermissions()
        emit("Permissions set")

        emit("Diagnostics...")
        val problems = paths.diagnose()
        if (problems.isNotEmpty()) {
            problems.forEach {
                emit("PROBLEM: $it")
                logger.log("DIAGNOSIS: $it")
            }
        } else {
            emit("All files present")
        }

        emit("Verifying Python 3.11...")
        val pythonOk = runtimeManager.verifyPython()
        if (!pythonOk) {
            emit("ERROR: Python 3.11 verification failed!")
            return@flow
        }
        emit("Python 3.11 OK ✓")

        emit("Verifying ffmpeg...")
        val ffmpegOk = runtimeManager.verifyFfmpeg()
        if (!ffmpegOk) {
            emit("ERROR: ffmpeg verification failed!")
            return@flow
        }
        emit("ffmpeg OK ✓")

        runtimeManager.markRuntimeReady()
        emit("Runtime initialization complete!")
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchVideoInfo(url: String): Result<VideoInfo> =
        withContext(Dispatchers.IO) {
            try {
                logger.log("Fetching video info for: $url")
                val result = processRunner.runPythonScript(listOf("info", url))

                if (result.exitCode != 0) {
                    val error = parseRunnerError(result.stdout, result.stderr)
                    logger.log("Error fetching info: $error")
                    return@withContext Result.failure(Exception(error))
                }

                val json = extractJson(result.stdout)
                if (json == null) {
                    logger.log("No JSON found in stdout:")
                    logger.log(result.stdout.take(3000))
                    logger.log("stderr:")
                    logger.log(result.stderr.take(3000))
                    return@withContext Result.failure(Exception("Failed to parse video info"))
                }

                val info = parseVideoInfo(json, url)
                logger.log("Parsed: '${info.title}', ${info.formats.size} formats")
                Result.success(info)
            } catch (e: Exception) {
                logger.log("Fetch error: ${e.message}")
                Result.failure(e)
            }
        }

    override suspend fun fetchPlaylistInfo(url: String): Result<PlaylistInfo> =
        withContext(Dispatchers.IO) {
            try {
                logger.log("Fetching playlist info for: $url")
                val result = processRunner.runPythonScript(listOf("playlist_info", url))

                if (result.exitCode != 0) {
                    val error = parseRunnerError(result.stdout, result.stderr)
                    logger.log("Playlist fetch error: $error")
                    return@withContext Result.failure(Exception(error))
                }

                val json = extractJson(result.stdout)
                if (json == null) {
                    logger.log("No JSON in playlist stdout: ${result.stdout.take(2000)}")
                    return@withContext Result.failure(Exception("Failed to parse playlist info"))
                }

                val root = JsonParser.parseString(json).asJsonObject
                val entries = mutableListOf<PlaylistItem>()

                root.getAsJsonArray("entries")?.forEachIndexed { i, el ->
                    try {
                        val obj = el.asJsonObject
                        val vidUrl = obj.getStr("url").orEmpty()
                        if (vidUrl.isBlank()) return@forEachIndexed
                        entries.add(
                            PlaylistItem(
                                index = obj.getInt("index") ?: i,
                                id = obj.getStr("id").orEmpty(),
                                title = obj.getStr("title") ?: "Video ${i + 1}",
                                duration = obj.getInt("duration"),
                                thumbnail = obj.getStr("thumbnail"),
                                url = vidUrl
                            )
                        )
                    } catch (e: Exception) {
                        logger.log("Skipping playlist entry $i: ${e.message}")
                    }
                }

                logger.log("Playlist '${root.getStr("title")}': ${entries.size} entries")
                Result.success(
                    PlaylistInfo(
                        title = root.getStr("title") ?: "Playlist",
                        uploader = root.getStr("uploader"),
                        entries = entries
                    )
                )
            } catch (e: Exception) {
                logger.log("Playlist fetch error: ${e.message}")
                Result.failure(e)
            }
        }

    override fun downloadVideo(
        url: String,
        selectedFormat: FormatItem,
        availableFormats: List<FormatItem>,
        outputDir: String,
        fileName: String?
    ): Flow<DownloadProgress> = flow {
        lastResult = null
        lastDownloadedFile = null

        try {
            logger.log("Starting smart download: url=$url, format=${selectedFormat.formatId}")

            when {
                selectedFormat.isMuxed -> {
                    emit(DownloadProgress(status = "Downloading muxed format..."))
                    val downloaded =
                        downloadSingleFormat(url, selectedFormat.formatId, outputDir, fileName)
                    lastDownloadedFile = downloaded.absolutePath
                    lastResult = DownloadResult.Success(downloaded, fileName ?: downloaded.name)
                    emit(
                        DownloadProgress(
                            percent = 100f,
                            status = "Complete!",
                            phase = DownloadProgress.Phase.COMPLETE
                        )
                    )
                }

                selectedFormat.isAudioOnly -> {
                    emit(DownloadProgress(status = "Downloading audio..."))
                    val downloaded =
                        downloadSingleFormat(url, selectedFormat.formatId, outputDir, fileName)
                    lastDownloadedFile = downloaded.absolutePath
                    lastResult = DownloadResult.Success(downloaded, fileName ?: downloaded.name)
                    emit(
                        DownloadProgress(
                            percent = 100f,
                            status = "Complete!",
                            phase = DownloadProgress.Phase.COMPLETE
                        )
                    )
                }

                selectedFormat.isVideoOnly -> {
                    val audioFormat = chooseBestAudioFormat(availableFormats, selectedFormat)

                    if (audioFormat == null) {
                        logger.log("No separate audio format found, trying fallbacks")
                        emit(
                            DownloadProgress(
                                percent = 0f,
                                status = "No separate audio found, trying full download...",
                                phase = DownloadProgress.Phase.DOWNLOADING
                            )
                        )

                        val baseName = fileName ?: "video"
                        val muxedFormat = chooseBestMuxedFormat(availableFormats)
                        if (muxedFormat != null) {
                            logger.log("Fallback 1: muxed format=${muxedFormat.formatId}")
                            val downloaded =
                                downloadSingleFormat(url, muxedFormat.formatId, outputDir, baseName)
                            lastDownloadedFile = downloaded.absolutePath
                            lastResult = DownloadResult.Success(downloaded, baseName)
                        } else {
                            logger.log("Fallback 2: bestvideo+bestaudio/best")
                            val downloaded = downloadBestFallback(url, outputDir, baseName)
                            lastDownloadedFile = downloaded.absolutePath
                            lastResult = DownloadResult.Success(downloaded, baseName)
                        }

                        emit(
                            DownloadProgress(
                                percent = 100f,
                                status = "Complete!",
                                phase = DownloadProgress.Phase.COMPLETE
                            )
                        )
                        return@flow
                    }

                    logger.log(
                        "Video-only format=${selectedFormat.formatId}, " +
                                "audio=${audioFormat.formatId}"
                    )

                    val baseName = fileName ?: "video"
                    val tempBase = "${baseName}_${System.currentTimeMillis()}"
                    val videoName = "${tempBase}_video"
                    val audioName = "${tempBase}_audio"

                    emit(
                        DownloadProgress(
                            percent = 0f,
                            status = "Downloading video stream...",
                            phase = DownloadProgress.Phase.DOWNLOADING
                        )
                    )
                    val videoFile =
                        downloadSingleFormat(url, selectedFormat.formatId, outputDir, videoName)

                    emit(
                        DownloadProgress(
                            percent = 0f,
                            status = "Downloading audio stream...",
                            phase = DownloadProgress.Phase.DOWNLOADING
                        )
                    )
                    val audioFile =
                        downloadSingleFormat(url, audioFormat.formatId, outputDir, audioName)

                    val finalExt = when {
                        selectedFormat.ext.equals("webm", true) ||
                                audioFormat.ext.equals("webm", true) -> "mkv"
                        else -> "mp4"
                    }
                    val finalFile = File(outputDir, "$baseName.$finalExt")

                    emit(
                        DownloadProgress(
                            percent = 99f,
                            status = "Merging video and audio...",
                            phase = DownloadProgress.Phase.MERGING
                        )
                    )
                    mergeFiles(videoFile, audioFile, finalFile)

                    if (!finalFile.exists() || finalFile.length() == 0L) {
                        throw Exception("Merged file not created")
                    }

                    videoFile.delete()
                    audioFile.delete()

                    lastDownloadedFile = finalFile.absolutePath
                    lastResult = DownloadResult.Success(finalFile, baseName)
                    emit(
                        DownloadProgress(
                            percent = 100f,
                            status = "Complete!",
                            phase = DownloadProgress.Phase.COMPLETE
                        )
                    )
                }

                else -> throw Exception("Unsupported format type")
            }
        } catch (e: PythonProcessRunner.ProcessCancelledException) {
            lastResult = DownloadResult.Cancelled
            emit(
                DownloadProgress(
                    status = "Cancelled",
                    phase = DownloadProgress.Phase.CANCELLED
                )
            )
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            logger.log("Download error: $msg")
            logger.log(e.stackTraceToString())
            lastResult = DownloadResult.Error(msg, classifyError(msg))
            emit(
                DownloadProgress(
                    status = "Error: $msg",
                    phase = DownloadProgress.Phase.ERROR
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override fun downloadAudio(
        url: String,
        availableFormats: List<FormatItem>,
        outputDir: String,
        fileName: String?
    ): Flow<DownloadProgress> = flow {
        lastResult = null
        lastDownloadedFile = null

        try {
            emit(DownloadProgress(status = "Selecting best audio..."))

            val audioFormat = chooseBestAudioOnly(availableFormats)
                ?: throw Exception("No audio-only format found")

            logger.log("Selected best audio format=${audioFormat.formatId}")

            val downloaded = downloadSingleFormat(
                url = url,
                formatId = audioFormat.formatId,
                outputDir = outputDir,
                fileName = fileName
            )

            lastDownloadedFile = downloaded.absolutePath
            lastResult = DownloadResult.Success(downloaded, fileName ?: downloaded.name)
            emit(
                DownloadProgress(
                    percent = 100f,
                    status = "Complete!",
                    phase = DownloadProgress.Phase.COMPLETE
                )
            )
        } catch (e: PythonProcessRunner.ProcessCancelledException) {
            lastResult = DownloadResult.Cancelled
            emit(
                DownloadProgress(
                    status = "Cancelled",
                    phase = DownloadProgress.Phase.CANCELLED
                )
            )
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            logger.log("Audio download error: $msg")
            logger.log(e.stackTraceToString())
            lastResult = DownloadResult.Error(msg, classifyError(msg))
            emit(
                DownloadProgress(
                    status = "Error: $msg",
                    phase = DownloadProgress.Phase.ERROR
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        processRunner.cancelCurrentProcess()
        currentFfmpegProcess?.let {
            try {
                it.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        currentFfmpegProcess = null
    }

    override fun getLastResult(): DownloadResult? = lastResult

    override fun getLastDownloadedFile(): String? = lastDownloadedFile

    // ─── Private ──────────────────────────────────────────────────────────────

    private suspend fun downloadSingleFormat(
        url: String,
        formatId: String,
        outputDir: String,
        fileName: String?
    ): File {
        var lastError: Exception? = null

        repeat(3) { attempt ->
            try {
                logger.log("Downloading format=$formatId (attempt ${attempt + 1}/3)")

                val args = mutableListOf(
                    "download_single",
                    url,
                    "--format", formatId,
                    "--output-dir", outputDir
                )
                fileName?.let {
                    args.add("--filename")
                    args.add(it)
                }

                var completedPath: String? = null

                processRunner.runPythonScriptWithProgress(args).collect { rawLine ->
                    val line = rawLine.trim()
                    logger.log("DL.SINGLE: $line")

                    val completeIndex = line.indexOf("COMPLETE:")
                    val fatalIndex = line.indexOf("FATAL:")

                    when {
                        completeIndex >= 0 ->
                            completedPath =
                                line.substring(completeIndex + "COMPLETE:".length).trim()

                        fatalIndex >= 0 ->
                            throw RuntimeException(line.substring(fatalIndex))
                    }
                }

                val direct = completedPath?.let { File(it) }
                if (direct != null && direct.exists()) return direct

                val byPrefix = if (!fileName.isNullOrBlank()) {
                    File(outputDir).listFiles()
                        ?.filter { it.isFile && it.nameWithoutExtension.startsWith(fileName) }
                        ?.maxByOrNull { it.lastModified() }
                } else null
                if (byPrefix != null && byPrefix.exists()) return byPrefix

                val latest = findLatestFile(outputDir)
                if (latest != null) return latest

                throw Exception("Downloaded file not found for format $formatId")

            } catch (e: Exception) {
                lastError = e
                val msg = e.message ?: ""
                val retryable = msg.contains("Connection reset", true) ||
                        msg.contains("timed out", true) ||
                        msg.contains("TransportError", true) ||
                        msg.contains("Network error", true) ||
                        msg.contains("reset by peer", true)

                logger.log("downloadSingleFormat failed (attempt ${attempt + 1}): $msg")

                if (!retryable || attempt == 2) throw e

                logger.log("Retrying format $formatId after transient error...")
                delay(1500L * (attempt + 1))
            }
        }

        throw lastError ?: Exception("Unknown download error for format $formatId")
    }

    private suspend fun downloadBestFallback(
        url: String,
        outputDir: String,
        fileName: String
    ): File {
        logger.log("downloadBestFallback: trying bestvideo+bestaudio/best")
        return try {
            downloadSingleFormat(url, "bestvideo+bestaudio/best", outputDir, fileName)
        } catch (e: Exception) {
            logger.log("bestvideo+bestaudio failed (${e.message}), falling back to best")
            downloadSingleFormat(url, "best", outputDir, fileName)
        }
    }

    private suspend fun mergeFiles(videoFile: File, audioFile: File, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val command = paths.buildFfmpegCommand(
            listOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", audioFile.absolutePath,
                "-c", "copy",
                outputFile.absolutePath
            )
        )

        logger.log("FFMPEG MERGE CMD: ${command.joinToString(" ")}")

        val pb = ProcessBuilder(command)
        pb.environment().clear()
        pb.environment().putAll(paths.buildEnvironment(processRunner.useUpdatedYtDlp))
        pb.redirectErrorStream(true)

        val process = pb.start()
        currentFfmpegProcess = process

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        currentFfmpegProcess = null

        logger.log("FFMPEG MERGE EXIT: $exitCode")
        logger.log("FFMPEG MERGE OUT: ${output.take(2000)}")

        if (exitCode != 0) {
            throw Exception("FFmpeg merge failed with exit code $exitCode")
        }
    }

    private fun chooseBestAudioFormat(
        formats: List<FormatItem>,
        videoFormat: FormatItem
    ): FormatItem? {
        var candidates = formats.filter { it.isAudioOnly }

        if (candidates.isEmpty()) {
            candidates = formats.filter {
                it.resolution.contains("audio", ignoreCase = true) && !it.isVideoOnly
            }
        }

        if (candidates.isEmpty()) return null

        return when {
            videoFormat.ext.equals("webm", true) -> {
                val webmAudio = candidates
                    .filter {
                        it.ext.equals("webm", true) ||
                                it.acodec?.contains("opus", true) == true
                    }
                    .maxWithOrNull(
                        compareBy<FormatItem> { it.abr ?: 0f }.thenBy { it.tbr ?: 0f }
                    )
                webmAudio ?: candidates.maxWithOrNull(
                    compareBy<FormatItem> { it.abr ?: 0f }.thenBy { it.tbr ?: 0f }
                )
            }

            videoFormat.ext.equals("mp4", true) -> {
                val mp4Audio = candidates
                    .filter {
                        it.ext.equals("m4a", true) ||
                                it.acodec?.contains("mp4a", true) == true
                    }
                    .maxWithOrNull(
                        compareBy<FormatItem> { it.abr ?: 0f }.thenBy { it.tbr ?: 0f }
                    )
                mp4Audio ?: candidates.maxWithOrNull(
                    compareBy<FormatItem> { it.abr ?: 0f }.thenBy { it.tbr ?: 0f }
                )
            }

            else -> candidates.maxWithOrNull(
                compareBy<FormatItem> { it.abr ?: 0f }.thenBy { it.tbr ?: 0f }
            )
        }
    }

    private fun chooseBestMuxedFormat(formats: List<FormatItem>): FormatItem? =
        formats
            .filter { it.isMuxed }
            .maxWithOrNull(
                compareBy<FormatItem> { it.height ?: 0 }.thenBy { it.tbr ?: 0f }
            )

    private fun chooseBestAudioOnly(formats: List<FormatItem>): FormatItem? {
        val audioOnly = formats.filter { it.isAudioOnly }
        if (audioOnly.isEmpty()) return null

        val preferred = audioOnly
            .filter {
                it.ext.equals("m4a", true) ||
                        it.acodec?.contains("mp4a", true) == true
            }
            .maxWithOrNull(
                compareBy<FormatItem> { it.abr ?: 0f }.thenBy { it.tbr ?: 0f }
            )

        return preferred ?: audioOnly.maxWithOrNull(
            compareBy<FormatItem> { it.abr ?: 0f }.thenBy { it.tbr ?: 0f }
        )
    }

    private fun parseRunnerError(stdout: String, stderr: String): String {
        val combined = buildString {
            if (stdout.isNotBlank()) append(stdout).append('\n')
            if (stderr.isNotBlank()) append(stderr)
        }

        return try {
            val fatalLine = combined.lines().firstOrNull { it.startsWith("FATAL:") }
            if (fatalLine != null) {
                val payload =
                    JsonParser.parseString(fatalLine.removePrefix("FATAL:")).asJsonObject
                val reason = payload.getStr("reason") ?: "UNKNOWN"
                val details = payload.getStr("details") ?: ""

                when (reason) {
                    "SSL_IMPORT_ERROR" -> "Python SSL import failed: ${details.take(400)}"
                    "HASHLIB_IMPORT_ERROR" -> "Python hashlib import failed: ${details.take(400)}"
                    "YTDLP_IMPORT_ERROR" -> "yt_dlp import failed: ${details.take(400)}"
                    "INFO_EXTRACTION_FAILED" -> "Failed to extract video info"
                    "INFO_EXCEPTION" -> "yt_dlp info exception: ${details.take(400)}"
                    "YTDLP_DOWNLOAD_ERROR" -> "yt_dlp download error: ${details.take(400)}"
                    "DOWNLOAD_EXCEPTION" -> "Download exception: ${details.take(400)}"
                    "OUTPUT_NOT_FOUND" -> "Output file not found"
                    "CANCELLED" -> "Cancelled"
                    else -> "$reason: ${details.take(400)}"
                }
            } else {
                parsePlainError(combined)
            }
        } catch (_: Exception) {
            parsePlainError(combined)
        }
    }

    private fun parsePlainError(text: String): String = when {
        text.contains("HTTP Error 403") || text.contains("Forbidden") ->
            "Access forbidden (403)"

        text.contains("Sign in to confirm your age") ||
                text.contains("age-restricted", true) ->
            "Age-restricted video"

        text.contains("Private video", true) ->
            "Private video"

        text.contains("Video unavailable", true) ->
            "Video unavailable"

        text.contains("Unsupported URL", true) ||
                text.contains("not a valid URL", true) ->
            "Invalid URL"

        text.contains("URLError") || text.contains("ConnectionError") ||
                text.contains("Connection reset", true) ->
            "Network error. Check internet connection."

        text.contains("No module named", true) ->
            "Python module error"

        text.contains("cannot locate symbol", true) ->
            "Native module ABI mismatch"

        text.isNotBlank() ->
            text.lines().lastOrNull { it.isNotBlank() }?.take(400) ?: text.take(400)

        else -> "Unknown error"
    }

    private fun extractJson(stdout: String): String? {
        val lines = stdout.trim().lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        }
        val full = stdout.trim()
        val start = full.indexOf('{')
        val end = full.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return full.substring(start, end + 1)
        }
        return null
    }

    private fun parseVideoInfo(json: String, url: String): VideoInfo {
        val root = JsonParser.parseString(json).asJsonObject
        val formats = mutableListOf<FormatItem>()

        root.getAsJsonArray("formats")?.forEach { element ->
            val obj = element.asJsonObject
            try {
                val vcodec = obj.getStr("vcodec")
                val acodec = obj.getStr("acodec")
                val resolution = obj.getStr("resolution")?.trim()?.lowercase() ?: ""

                val hasVideo = vcodec != null && vcodec != "none"
                val hasAudio =
                    (acodec != null && acodec != "none") || resolution.contains("audio")

                if (!hasVideo && !hasAudio) return@forEach

                val height = obj.getInt("height")
                val width = obj.getInt("width")
                val resolDisplay = when {
                    height != null && width != null -> "${width}x${height}"
                    height != null -> "${height}p"
                    hasAudio && !hasVideo -> "audio only"
                    else -> "unknown"
                }

                formats.add(
                    FormatItem(
                        formatId = obj.getStr("format_id") ?: return@forEach,
                        ext = obj.getStr("ext") ?: "mp4",
                        resolution = resolDisplay,
                        filesize = obj.getLong("filesize") ?: obj.getLong("filesize_approx"),
                        fps = obj.getInt("fps"),
                        vcodec = vcodec,
                        acodec = acodec,
                        abr = obj.getFloat("abr"),
                        tbr = obj.getFloat("tbr"),
                        formatNote = obj.getStr("format_note"),
                        hasVideo = hasVideo,
                        hasAudio = hasAudio,
                        height = height,
                        width = width
                    )
                )
            } catch (e: Exception) {
                logger.log("Skipping format: ${e.message}")
            }
        }

        return VideoInfo(
            title = root.getStr("title") ?: "Unknown",
            duration = root.getInt("duration"),
            thumbnail = root.getStr("thumbnail"),
            uploader = root.getStr("uploader"),
            description = root.getStr("description"),
            formats = formats,
            url = url
        )
    }

    private fun findLatestFile(dir: String): File? =
        File(dir).listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }

    private fun classifyError(msg: String): DownloadResult.ErrorCode = when {
        msg.contains("403") || msg.contains("Forbidden") ->
            DownloadResult.ErrorCode.FORBIDDEN

        msg.contains("age-restricted", true) ||
                msg.contains("Sign in to confirm", true) ->
            DownloadResult.ErrorCode.AGE_RESTRICTED

        msg.contains("Private video", true) ->
            DownloadResult.ErrorCode.PRIVATE_VIDEO

        msg.contains("unavailable", true) ->
            DownloadResult.ErrorCode.VIDEO_UNAVAILABLE

        msg.contains("Invalid URL", true) || msg.contains("Unsupported", true) ->
            DownloadResult.ErrorCode.INVALID_URL

        msg.contains("Network", true) || msg.contains("Connection", true) ||
                msg.contains("reset by peer", true) ->
            DownloadResult.ErrorCode.NETWORK_ERROR

        msg.contains("merge", true) || msg.contains("ffmpeg", true) ->
            DownloadResult.ErrorCode.MERGE_FAILED

        msg.contains("module", true) || msg.contains("Import", true) ||
                msg.contains("symbol", true) ->
            DownloadResult.ErrorCode.PYTHON_ERROR

        else ->
            DownloadResult.ErrorCode.UNKNOWN
    }

    private fun JsonObject.getStr(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.getInt(key: String): Int? =
        try {
            if (has(key) && !get(key).isJsonNull) get(key).asInt else null
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.getLong(key: String): Long? =
        try {
            if (has(key) && !get(key).isJsonNull) get(key).asLong else null
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.getFloat(key: String): Float? =
        try {
            if (has(key) && !get(key).isJsonNull) get(key).asFloat else null
        } catch (_: Exception) {
            null
        }
}