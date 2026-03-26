package com.example.ytdownloader.runtime

import com.example.ytdownloader.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class PythonProcessRunner(
    private val paths: RuntimePaths,
    private val logger: FileLogger
) {
    @Volatile
    private var currentProcess: Process? = null

    @Volatile
    private var isCancelled: Boolean = false

    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    class ProcessCancelledException : Exception("Process was cancelled by user")

    private fun buildCommand(args: List<String>): List<String> {
        return listOf(
            paths.linkerPath,
            paths.pythonBin,
            paths.ytRunnerScript
        ) + args
    }

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

    suspend fun runPythonScript(args: List<String>): ProcessResult = withContext(Dispatchers.IO) {
        isCancelled = false

        val command = buildCommand(args)
        val env = paths.buildEnvironment()

        logger.log("=== Run Python ===")
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

            val exitCode = process.waitFor()
            stdoutThread.join(10000)
            stderrThread.join(10000)
            currentProcess = null

            if (isCancelled) throw ProcessCancelledException()

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

    fun runPythonScriptWithProgress(args: List<String>): Flow<String> = flow {
        isCancelled = false

        val command = buildCommand(args)
        val env = paths.buildEnvironment()

        logger.log("=== Run Python (progress) ===")
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

                        if (!isIgnorableYtWarning(safeLine)) {
                            logger.log("PY.ERR: $safeLine")
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        stderrThread.start()

        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (isCancelled) {
                        process.destroyForcibly()
                        throw ProcessCancelledException()
                    }
                    logger.log("PY.OUT: $line")
                    emit(line!!)
                }
            }

            val exitCode = process.waitFor()
            stderrThread.join(10000)
            currentProcess = null

            if (isCancelled) throw ProcessCancelledException()
            if (exitCode != 0) {
                throw RuntimeException("Python exit $exitCode: ${stderrBuilder.toString().take(500)}")
            }
        } catch (e: ProcessCancelledException) {
            currentProcess = null
            throw e
        } catch (e: Exception) {
            currentProcess = null
            if (isCancelled) throw ProcessCancelledException()
            throw e
        }
    }.flowOn(Dispatchers.IO)

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