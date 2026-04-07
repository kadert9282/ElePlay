package com.example.ytdownloader.runtime

import com.example.ytdownloader.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class PythonProcessRunner(
    private val paths: RuntimePaths,
    private val logger: FileLogger
) {
    @Volatile
    private var currentProcess: Process? = null

    @Volatile
    private var isCancelled: Boolean = false

    @Volatile
    private var lastOutputTimeMs: Long = 0L

    @Volatile
    private var watchdogTriggered: Boolean = false

    // Флаг — использовать ли обновлённый yt-dlp
    @Volatile
    var useUpdatedYtDlp: Boolean = false

    companion object {
        private const val PROGRESS_TIMEOUT_MS = 60_000L
        private const val PROCESS_HARD_TIMEOUT_MS = 300_000L
    }

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    class ProcessCancelledException : Exception("Process was cancelled by user")

    class NetworkTimeoutException(message: String) : Exception(message)

    // ─── Command builder ───────────────────────────────────────────────────────

    private fun buildCommand(args: List<String>): List<String> {
        return listOf(
            paths.linkerPath,
            paths.pythonBin,
            paths.ytRunnerScript
        ) + args
    }

    // ─── Warning filter ────────────────────────────────────────────────────────

    private fun isIgnorableYtWarning(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("no supported javascript runtime could be found") ||
                lower.contains("youtube extraction without a js runtime has been deprecated") ||
                lower.contains("github.com/yt-dlp/yt-dlp/wiki/ejs") ||
                lower.contains("writing dash m4a") ||
                lower.contains("writing dash mp4") ||
                lower.contains("install ffmpeg to fix this automatically") ||
                lower.contains("only some players support this container")
    }

    // ─── runPythonScript ───────────────────────────────────────────────────────

    suspend fun runPythonScript(args: List<String>): ProcessResult =
        withContext(Dispatchers.IO) {
            isCancelled = false

            val command = buildCommand(args)
            // Передаём флаг useUpdatedYtDlp в buildEnvironment
            val env = paths.buildEnvironment(useUpdatedYtDlp)

            logger.log("=== Run Python ===")
            logger.log("useUpdatedYtDlp=$useUpdatedYtDlp")
            logger.log("Cmd: ${command.joinToString(" ")}")

            val pb = ProcessBuilder(command)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(java.io.File(paths.scriptsDir))

            try {
                val process = pb.start()
                currentProcess = process

                val stdoutBuilder = StringBuilder()
                val stderrBuilder = StringBuilder()

                val stdoutThread = Thread {
                    try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                stdoutBuilder.appendLine(line)
                                logger.log("PY.OUT: $line")
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                val stderrThread = Thread {
                    try {
                        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val safeLine = line ?: continue
                                stderrBuilder.appendLine(safeLine)
                                if (!isIgnorableYtWarning(safeLine)) {
                                    logger.log("PY.ERR: $safeLine")
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                stdoutThread.start()
                stderrThread.start()

                val finished = process.waitFor(
                    PROCESS_HARD_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )

                if (!finished) {
                    logger.log("Process hard timeout (${PROCESS_HARD_TIMEOUT_MS / 1000}s), killing")
                    process.destroyForcibly()
                    currentProcess = null
                    return@withContext ProcessResult(
                        -1,
                        stdoutBuilder.toString(),
                        "Process timeout after ${PROCESS_HARD_TIMEOUT_MS / 1000}s"
                    )
                }

                stdoutThread.join(10000)
                stderrThread.join(10000)
                currentProcess = null

                if (isCancelled) throw ProcessCancelledException()

                val exitCode = process.exitValue()
                logger.log("Python exit: $exitCode")
                ProcessResult(exitCode, stdoutBuilder.toString(), stderrBuilder.toString())

            } catch (e: ProcessCancelledException) {
                throw e
            } catch (e: Exception) {
                currentProcess = null
                logger.log("Process failed: ${e.message}")
                ProcessResult(-1, "", e.message ?: "Process failed")
            }
        }

    // ─── runPythonScriptWithProgress ───────────────────────────────────────────

    fun runPythonScriptWithProgress(args: List<String>): Flow<String> = flow {
        isCancelled = false
        lastOutputTimeMs = System.currentTimeMillis()
        watchdogTriggered = false

        val command = buildCommand(args)
        // Передаём флаг useUpdatedYtDlp в buildEnvironment
        val env = paths.buildEnvironment(useUpdatedYtDlp)

        logger.log("=== Run Python (progress) ===")
        logger.log("useUpdatedYtDlp=$useUpdatedYtDlp")
        logger.log("Cmd: ${command.joinToString(" ")}")

        val pb = ProcessBuilder(command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(java.io.File(paths.scriptsDir))
        pb.redirectErrorStream(false)

        val process = pb.start()
        currentProcess = process

        val stderrBuilder = StringBuilder()

        val stderrThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val safeLine = line ?: continue
                        stderrBuilder.appendLine(safeLine)
                        lastOutputTimeMs = System.currentTimeMillis()
                        if (!isIgnorableYtWarning(safeLine)) {
                            logger.log("PY.ERR: $safeLine")
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val watchdogThread = Thread {
            try {
                while (process.isAlive && !isCancelled) {
                    Thread.sleep(5000L)
                    val silentMs = System.currentTimeMillis() - lastOutputTimeMs
                    if (silentMs > PROGRESS_TIMEOUT_MS) {
                        logger.log(
                            "Watchdog: no output for ${silentMs / 1000}s, killing process"
                        )
                        watchdogTriggered = true
                        process.destroyForcibly()
                        break
                    }
                }
            } catch (_: InterruptedException) {
            }
        }
        watchdogThread.isDaemon = true

        stderrThread.start()
        watchdogThread.start()

        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (isCancelled) {
                        process.destroyForcibly()
                        throw ProcessCancelledException()
                    }
                    lastOutputTimeMs = System.currentTimeMillis()
                    logger.log("PY.OUT: $line")
                    emit(line!!)
                }
            }

            val exitCode = process.waitFor()
            watchdogThread.interrupt()
            stderrThread.join(10000)
            currentProcess = null

            if (isCancelled) throw ProcessCancelledException()

            if (watchdogTriggered) {
                throw NetworkTimeoutException(
                    "No response from download process for " +
                            "${PROGRESS_TIMEOUT_MS / 1000}s — " +
                            "possible network disconnection"
                )
            }

            if (exitCode != 0) {
                throw RuntimeException(
                    "Python exit $exitCode: ${stderrBuilder.toString().take(500)}"
                )
            }

        } catch (e: ProcessCancelledException) {
            watchdogThread.interrupt()
            currentProcess = null
            throw e
        } catch (e: NetworkTimeoutException) {
            watchdogThread.interrupt()
            currentProcess = null
            throw e
        } catch (e: Exception) {
            watchdogThread.interrupt()
            currentProcess = null
            if (isCancelled) throw ProcessCancelledException()
            throw e
        }

    }.flowOn(Dispatchers.IO)

    // ─── Cancel ────────────────────────────────────────────────────────────────

    fun cancelCurrentProcess() {
        isCancelled = true
        currentProcess?.let {
            try {
                it.destroyForcibly()
                logger.log("Process destroyed")
            } catch (e: Exception) {
                logger.log("Destroy failed: ${e.message}")
            }
        }
        currentProcess = null
    }
}