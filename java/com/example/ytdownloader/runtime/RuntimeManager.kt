package com.example.ytdownloader.runtime

import android.content.Context
import android.system.Os
import com.example.ytdownloader.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class RuntimeManager(
    private val context: Context,
    private val logger: FileLogger
) {
    companion object {
        private const val RUNTIME_VERSION = "ready_v_ffmpeg_final_node_qjs_playlist_v2"
    }

    val paths = RuntimePaths(context)

    fun isRuntimeReady(): Boolean {
        val marker = File(paths.readyMarker)
        if (!marker.exists()) return false

        val markerText = runCatching { marker.readText().trim() }.getOrDefault("")
        if (markerText != RUNTIME_VERSION) return false

        val checks = listOf(
            File(paths.pythonBin),
            File(paths.pythonSharedLib),
            File(paths.pythonSharedLib10),
            File(paths.ffmpegPath),
            File(paths.ffmpegRealPath),
            File(paths.libSsl),
            File(paths.libSsl3),
            File(paths.libCrypto),
            File(paths.libCrypto3),
            File(paths.qjsPath),
            File(paths.nodePath),
            File(paths.pythonStdlib, "os.py"),
            File(paths.pythonStdlib, "ssl.py"),
            File(paths.pythonStdlib, "hashlib.py"),
            File(paths.ytRunnerScript),
            File(paths.sitePackages, "yt_dlp/__init__.py")
        )

        return checks.all { it.exists() }
    }

    fun markRuntimeReady() {
        File(paths.readyMarker).writeText(RUNTIME_VERSION)
        logger.log("Runtime marked as ready: $RUNTIME_VERSION")
    }

    fun clearRuntime() {
        val baseDir = File(context.filesDir, "runtime")
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
        }
        logger.log("Runtime cleared")
    }

    suspend fun extractRuntime(progressCallback: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            paths.ensureDirectories()
        }
    }

    suspend fun extractBinaries() = withContext(Dispatchers.IO) {
        logger.log("=== Extracting all binaries from assets/bin/ ===")

        val binDir = File(paths.pythonBin).parentFile!!
        binDir.mkdirs()

        val assetFiles = context.assets.list("bin") ?: emptyArray()
        logger.log("Found ${assetFiles.size} files in assets/bin/")

        for (fileName in assetFiles) {
            val destFile = if (fileName == "ffmpeg") {
                File(paths.ffmpegRealPath)
            } else {
                File(binDir, fileName)
            }

            logger.log("Extracting bin/$fileName -> ${destFile.name}")
            copyAssetFile("bin/$fileName", destFile)
            makeExecutable(destFile)
            logger.log("  ${destFile.name}: ${destFile.length()} bytes, exec=${destFile.canExecute()}")
        }

        createSymlinkIfNeeded(binDir, "libpython3.11.so.1.0", "libpython3.11.so")
        createSymlinkIfNeeded(binDir, "libssl.so.3", "libssl.so")
        createSymlinkIfNeeded(binDir, "libcrypto.so.3", "libcrypto.so")

        createFfmpegWrapper()

        logger.log("Linker: ${paths.linkerPath}, exists=${File(paths.linkerPath).exists()}")
        logger.log("QJS path: ${paths.qjsPath}, exists=${File(paths.qjsPath).exists()}")
        logger.log("Node wrapper path: ${paths.nodePath}, exists=${File(paths.nodePath).exists()}")

        logger.log("=== bin/ contents ===")
        binDir.listFiles()?.sortedBy { it.name }?.forEach {
            logger.log("  ${it.name}: ${it.length()} bytes")
        }

        logger.log("=== Binaries ready ===")
    }

    private fun createFfmpegWrapper() {
        val wrapper = File(paths.ffmpegPath)
        val content = """
            #!/system/bin/sh
            export LD_LIBRARY_PATH="${File(paths.ffmpegRealPath).parentFile?.absolutePath}"
            exec ${paths.linkerPath} ${paths.ffmpegRealPath} "$@"
        """.trimIndent()

        wrapper.parentFile?.mkdirs()
        wrapper.writeText(content)
        makeExecutable(wrapper)
        logger.log("Created ffmpeg wrapper: ${wrapper.absolutePath}")
    }

    private fun createSymlinkIfNeeded(dir: File, targetName: String, linkName: String) {
        val target = File(dir, targetName)
        val link = File(dir, linkName)

        if (!target.exists()) return
        if (link.exists()) return

        try {
            Os.symlink(target.absolutePath, link.absolutePath)
            logger.log("Symlink: $linkName -> $targetName")
        } catch (e: Exception) {
            logger.log("Symlink failed for $linkName (${e.message}), copying instead")
            try {
                target.inputStream().use { input ->
                    FileOutputStream(link).use { output ->
                        input.copyTo(output, 16384)
                        output.flush()
                        output.fd.sync()
                    }
                }
                makeExecutable(link)
                logger.log("Copied fallback: $linkName")
            } catch (e2: Exception) {
                logger.log("Copy fallback failed for $linkName: ${e2.message}")
            }
        }
    }

    suspend fun extractPythonStdlib() = withContext(Dispatchers.IO) {
        val targetDir = File(paths.pythonStdlib)
        logger.log("Extracting stdlib -> ${targetDir.absolutePath}")
        targetDir.mkdirs()
        copyAssetDir("python311-stdlib", targetDir)

        logger.log("os.py: ${File(targetDir, "os.py").exists()}")
        val dynloadDir = File(targetDir, "lib-dynload")
        if (dynloadDir.exists()) {
            val count = dynloadDir.listFiles()?.count { it.name.endsWith(".so") } ?: 0
            logger.log("lib-dynload: $count .so files")
        }
        logger.log("Stdlib OK")
    }

    suspend fun extractSitePackages() = withContext(Dispatchers.IO) {
        logger.log("Extracting site-packages ZIP -> ${paths.sitePackages}")

        val targetDir = File(paths.sitePackages)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        unzipAsset("site-packages.zip", targetDir)

        val ytDlpInit = File(paths.sitePackages, "yt_dlp/__init__.py")
        logger.log("yt_dlp: ${ytDlpInit.exists()}")

        val certifiDir = File(paths.sitePackages, "certifi")
        val certifiPem = File(paths.sitePackages, "certifi/cacert.pem")
        logger.log("certifi dir exists: ${certifiDir.exists()}")
        logger.log("certifi/cacert.pem exists: ${certifiPem.exists()}")

        val jscDir = File(paths.sitePackages, "yt_dlp/extractor/youtube/jsc")
        val builtinDir = File(paths.sitePackages, "yt_dlp/extractor/youtube/jsc/_builtin")
        val builtinInit = File(paths.sitePackages, "yt_dlp/extractor/youtube/jsc/_builtin/__init__.py")
        val builtinBun = File(paths.sitePackages, "yt_dlp/extractor/youtube/jsc/_builtin/bun.py")
        val vendorDir = File(paths.sitePackages, "yt_dlp/extractor/youtube/jsc/_builtin/vendor")

        logger.log("jsc dir exists: ${jscDir.exists()}")
        logger.log("_builtin dir exists: ${builtinDir.exists()}")
        logger.log("_builtin/__init__.py exists: ${builtinInit.exists()}")
        logger.log("_builtin/bun.py exists: ${builtinBun.exists()}")
        logger.log("_builtin/vendor exists: ${vendorDir.exists()}")

        if (builtinDir.exists()) {
            builtinDir.listFiles()?.forEach {
                logger.log("  _builtin item: ${it.name} (${if (it.isDirectory) "dir" else "file"})")
            }
        }

        logger.log("Site-packages OK")
    }

    suspend fun extractScripts() = withContext(Dispatchers.IO) {
        logger.log("Extracting scripts -> ${paths.scriptsDir}")

        val dir = File(paths.scriptsDir)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        dir.mkdirs()

        copyAssetDir("scripts", dir)

        val runner = File(paths.ytRunnerScript)
        logger.log("yt_runner.py: ${runner.exists()}, ${runner.length()} bytes")

        if (runner.exists()) {
            val preview = runCatching { runner.readText().take(500) }.getOrDefault("")
            logger.log("yt_runner.py preview: $preview")
        }

        logger.log("Scripts OK")
    }

    suspend fun setPermissions() = withContext(Dispatchers.IO) {
        logger.log("Setting permissions...")

        val binDir = File(paths.pythonBin).parentFile
        binDir?.listFiles()?.forEach { file ->
            if (
                file.name.endsWith(".so") ||
                file.name.contains(".so.") ||
                file.name == "python3.11" ||
                file.name == "ffmpeg" ||
                file.name == "ffmpeg.bin" ||
                file.name == "qjs" ||
                file.name == "node"
            ) {
                makeExecutable(file)
            }
        }

        val dynloadDir = File(paths.pythonDynload)
        if (dynloadDir.exists()) {
            var count = 0
            dynloadDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".so")) {
                    makeExecutable(file)
                    count++
                }
            }
            logger.log("lib-dynload: $count files chmod'd")
        }

        setAllSoExecutable(File(paths.pythonStdlib))
        makeExecutable(File(paths.ytRunnerScript))
        logger.log("Permissions OK")
    }

    suspend fun verifyPython(): Boolean = withContext(Dispatchers.IO) {
        try {
            val problems = paths.diagnose()
            if (problems.isNotEmpty()) {
                logger.log("Diagnosis issues:")
                problems.forEach { logger.log("  $it") }
            }

            val env = paths.buildEnvironment()
            logger.log("=== Python Verification ===")
            logger.log("  Linker:           ${paths.linkerPath}")
            logger.log("  Binary:           ${paths.pythonBin}")
            logger.log("  QJS:              ${paths.qjsPath}")
            logger.log("  Node wrapper:     ${paths.nodePath}")
            logger.log("  LD_LIBRARY_PATH:  ${env["LD_LIBRARY_PATH"]}")
            logger.log("  PYTHONHOME:       ${env["PYTHONHOME"]}")
            logger.log("  SSL_CERT_FILE:    ${env["SSL_CERT_FILE"]}")
            logger.log("  YTDLP_NODE_PATH:  ${env["YTDLP_NODE_PATH"]}")

            File(paths.pythonBin).parentFile?.listFiles()?.forEach {
                logger.log("  bin/${it.name}: ${it.length()} bytes")
            }

            val command = paths.buildPythonCommand(listOf("--version"))
            logger.log("Command: ${command.joinToString(" ")}")

            val pb = ProcessBuilder(command)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
            val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.waitFor()

            logger.log("Exit: $exitCode")
            logger.log("Stdout: '$stdout'")
            if (stderr.isNotEmpty()) logger.log("Stderr: '$stderr'")

            val ok = exitCode == 0 && (stdout.contains("Python 3.11") || stderr.contains("Python 3.11"))
            if (ok) logger.log("Python 3.11 OK!")
            else logger.log("Python FAILED")
            ok
        } catch (e: Exception) {
            logger.log("Python exception: ${e.message}")
            logger.log(e.stackTraceToString())
            false
        }
    }

    suspend fun verifyFfmpeg(): Boolean = withContext(Dispatchers.IO) {
        try {
            val command = paths.buildFfmpegCommand(listOf("-version"))
            logger.log("ffmpeg command: ${command.joinToString(" ")}")

            val env = paths.buildEnvironment()
            val pb = ProcessBuilder(command)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)

            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.waitFor()

            logger.log("ffmpeg: exit=$exitCode, output='${output.take(200)}'")
            exitCode == 0 && output.contains("ffmpeg")
        } catch (e: Exception) {
            logger.log("ffmpeg failed: ${e.message}")
            false
        }
    }

    private fun unzipAsset(assetName: String, destDir: File) {
        context.assets.open(assetName).use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
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
                            fos.fd.sync()
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun makeExecutable(file: File) {
        if (!file.exists()) return
        file.setReadable(true, false)
        file.setExecutable(true, false)
        file.setWritable(true, false)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath)).waitFor()
        } catch (_: Exception) {
        }
        try {
            Os.chmod(file.absolutePath, 493)
        } catch (_: Exception) {
        }
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val list: Array<String>?
        try {
            list = context.assets.list(assetPath)
        } catch (e: IOException) {
            logger.log("Cannot list '$assetPath': ${e.message}")
            return
        }

        if (list == null || list.isEmpty()) {
            destDir.parentFile?.mkdirs()
            copyAssetFile(assetPath, destDir)
            return
        }

        destDir.mkdirs()
        for (item in list) {
            val sub = "$assetPath/$item"
            val dest = File(destDir, item)
            try {
                val subList = context.assets.list(sub)
                if (subList != null && subList.isNotEmpty()) {
                    copyAssetDir(sub, dest)
                } else {
                    copyAssetFile(sub, dest)
                }
            } catch (e: IOException) {
                logger.log("Error copying '$sub': ${e.message}")
            }
        }
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        try {
            destFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, 16384)
                    output.flush()
                    output.fd.sync()
                }
            }
        } catch (e: IOException) {
            logger.log("FAILED copy '$assetPath': ${e.message}")
            throw e
        }
    }

    private fun setAllSoExecutable(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) setAllSoExecutable(file)
            else if (file.name.endsWith(".so")) {
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
        }
    }
}