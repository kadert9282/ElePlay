package com.example.ytdownloader.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLogger(context: Context) {

    private val logFile: File = File(context.filesDir, "app.log")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    init {
        // Create log file if it doesn't exist
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
        // Truncate if too large (> 5MB)
        if (logFile.length() > 5 * 1024 * 1024) {
            truncateLog()
        }
    }

    fun log(message: String) {
        synchronized(lock) {
            try {
                val timestamp = dateFormat.format(Date())
                val line = "[$timestamp] $message\n"
                FileWriter(logFile, true).use { writer ->
                    writer.write(line)
                }
                // Also log to logcat
                android.util.Log.d("YtDownloader", message)
            } catch (e: Exception) {
                android.util.Log.e("YtDownloader", "Failed to write log: ${e.message}")
            }
        }
    }

    fun logError(message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            try {
                val timestamp = dateFormat.format(Date())
                PrintWriter(FileWriter(logFile, true)).use { writer ->
                    writer.println("[$timestamp] ERROR: $message")
                    throwable?.printStackTrace(writer)
                }
                android.util.Log.e("YtDownloader", message, throwable)
            } catch (e: Exception) {
                android.util.Log.e("YtDownloader", "Failed to write error log: ${e.message}")
            }
        }
    }

    fun getLogFile(): File = logFile

    fun getLogContent(): String {
        return try {
            if (logFile.exists()) logFile.readText() else ""
        } catch (e: Exception) {
            "Failed to read log: ${e.message}"
        }
    }

    fun clear() {
        synchronized(lock) {
            logFile.writeText("")
        }
    }

    private fun truncateLog() {
        try {
            val lines = logFile.readLines()
            val keepLines = lines.takeLast(1000)
            logFile.writeText(keepLines.joinToString("\n"))
        } catch (e: Exception) {
            logFile.writeText("")
        }
    }
}