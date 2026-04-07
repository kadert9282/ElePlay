package com.example.ytdownloader.runtime

import android.content.Context
import java.io.File

class RuntimePaths(private val context: Context) {

    private val baseDir: File = File(context.filesDir, "runtime")
    private val localBinDir: File get() = File(baseDir, "bin")

    // Python
    val pythonBin: String get() = File(localBinDir, "python3.11").absolutePath
    val pythonSharedLib: String get() = File(localBinDir, "libpython3.11.so").absolutePath
    val pythonSharedLib10: String get() = File(localBinDir, "libpython3.11.so.1.0").absolutePath

    // JS runtimes
    val qjsPath: String get() = File(localBinDir, "qjs").absolutePath
    val nodePath: String get() = File(localBinDir, "node").absolutePath

    // FFmpeg
    val ffmpegRealPath: String get() = File(localBinDir, "ffmpeg.bin").absolutePath
    val ffmpegPath: String get() = File(localBinDir, "ffmpeg").absolutePath

    // OpenSSL
    val libSsl: String get() = File(localBinDir, "libssl.so").absolutePath
    val libSsl3: String get() = File(localBinDir, "libssl.so.3").absolutePath
    val libCrypto: String get() = File(localBinDir, "libcrypto.so").absolutePath
    val libCrypto3: String get() = File(localBinDir, "libcrypto.so.3").absolutePath
    val libFfi: String get() = File(localBinDir, "libffi.so").absolutePath

    // Python home / stdlib
    private val pythonHomeDir: File get() = File(baseDir, "python311-home")
    val pythonHome: String get() = pythonHomeDir.absolutePath
    val pythonStdlib: String get() = File(pythonHomeDir, "lib/python3.11").absolutePath
    val pythonDynload: String get() = File(pythonHomeDir, "lib/python3.11/lib-dynload").absolutePath

    // Packages / scripts
    val sitePackages: String get() = File(baseDir, "site-packages").absolutePath

    // ── Папка для обновлённого yt-dlp ─────────────────────────────────────────
    val sitePackagesUpdate: String get() = File(baseDir, "site-packages-update").absolutePath

    val scriptsDir: String get() = File(baseDir, "scripts").absolutePath
    val ytRunnerScript: String get() = File(baseDir, "scripts/yt_runner.py").absolutePath

    // Temp / marker
    val tempDir: String get() = File(baseDir, "tmp").absolutePath
    val readyMarker: String get() = File(baseDir, ".ready").absolutePath

    // Android linker
    val linkerPath: String
        get() = when {
            File("/system/bin/linker64").exists() -> "/system/bin/linker64"
            File("/system/bin/linker").exists() -> "/system/bin/linker"
            else -> "/system/bin/linker64"
        }

    fun ensureDirectories() {
        listOf(
            baseDir,
            localBinDir,
            pythonHomeDir,
            File(pythonStdlib),
            File(sitePackages),
            File(sitePackagesUpdate),
            File(scriptsDir),
            File(tempDir)
        ).forEach { it.mkdirs() }
    }

    fun buildEnvironment(useUpdatedYtDlp: Boolean = false): Map<String, String> {
        val env = mutableMapOf<String, String>()

        env["LD_LIBRARY_PATH"] = listOf(
            localBinDir.absolutePath,
            pythonDynload
        ).joinToString(":")

        env["PYTHONHOME"] = pythonHome

        // Если режим updated и папка существует — ставим её первой в PYTHONPATH
        val updateDir = File(sitePackagesUpdate)
        val hasUpdate = useUpdatedYtDlp &&
                File(sitePackagesUpdate, "yt_dlp/__init__.py").exists()

        val pythonPathParts = mutableListOf<String>()
        pythonPathParts.add(pythonStdlib)
        pythonPathParts.add(pythonDynload)
        if (hasUpdate) {
            // Обновлённый идёт первым — Python импортирует его раньше встроенного
            pythonPathParts.add(sitePackagesUpdate)
        }
        pythonPathParts.add(sitePackages)

        env["PYTHONPATH"] = pythonPathParts.joinToString(":")

        // Флаг для yt_runner.py чтобы он тоже знал какой режим
        env["YTDLP_USE_UPDATED"] = if (hasUpdate) "1" else "0"

        env["PATH"] = listOf(
            localBinDir.absolutePath,
            "/system/bin",
            "/vendor/bin"
        ).joinToString(":")

        env["TMPDIR"] = tempDir
        env["HOME"] = baseDir.absolutePath
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        env["PYTHONUNBUFFERED"] = "1"

        val certFile = File(sitePackages, "certifi/cacert.pem")
        if (certFile.exists()) {
            env["SSL_CERT_FILE"] = certFile.absolutePath
        }

        val nodeFile = File(nodePath)
        if (nodeFile.exists()) {
            env["YTDLP_NODE_PATH"] = nodeFile.absolutePath
        }

        val ffmpegFile = File(ffmpegPath)
        if (ffmpegFile.exists()) {
            env["YTDLP_FFMPEG_PATH"] = ffmpegFile.absolutePath
        }

        return env
    }

    fun buildPythonCommand(args: List<String>): List<String> {
        return listOf(linkerPath, pythonBin) + args
    }

    fun buildFfmpegCommand(args: List<String>): List<String> {
        return listOf(linkerPath, ffmpegRealPath) + args
    }

    fun diagnose(): List<String> {
        val problems = mutableListOf<String>()

        val checks = mapOf(
            "python3.11" to File(pythonBin),
            "libpython3.11.so" to File(pythonSharedLib),
            "libpython3.11.so.1.0" to File(pythonSharedLib10),
            "ffmpeg wrapper" to File(ffmpegPath),
            "ffmpeg real" to File(ffmpegRealPath),
            "libssl.so" to File(libSsl),
            "libssl.so.3" to File(libSsl3),
            "libcrypto.so" to File(libCrypto),
            "libcrypto.so.3" to File(libCrypto3),
            "libffi.so" to File(libFfi),
            "qjs" to File(qjsPath),
            "node wrapper" to File(nodePath),
            "linker" to File(linkerPath),
            "stdlib" to File(pythonStdlib),
            "os.py" to File(pythonStdlib, "os.py"),
            "ssl.py" to File(pythonStdlib, "ssl.py"),
            "hashlib.py" to File(pythonStdlib, "hashlib.py"),
            "lib-dynload" to File(pythonDynload),
            "_ssl module" to findFirstMatch(File(pythonDynload), "_ssl"),
            "_hashlib module" to findFirstMatch(File(pythonDynload), "_hashlib"),
            "yt_dlp" to File(sitePackages, "yt_dlp/__init__.py"),
            "yt_runner.py" to File(ytRunnerScript)
        )

        for ((name, file) in checks) {
            if (file == null || !file.exists()) {
                problems.add("MISSING: $name")
            } else if (file.isFile && file.length() == 0L) {
                problems.add("EMPTY: $name -> ${file.absolutePath}")
            }
        }

        return problems
    }

    private fun findFirstMatch(dir: File, prefix: String): File? {
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull {
            it.name.startsWith(prefix) && it.name.endsWith(".so")
        }
    }
}