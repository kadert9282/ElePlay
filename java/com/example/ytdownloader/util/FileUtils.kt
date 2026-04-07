package com.example.ytdownloader.util

import java.io.File
import java.io.InputStream

object FileUtils {

    fun copyInputStreamToFile(inputStream: InputStream, destFile: File) {
        destFile.parentFile?.mkdirs()
        destFile.outputStream().use { output ->
            inputStream.copyTo(output, bufferSize = 8192)
        }
    }

    fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        file.delete()
    }

    fun getDirectorySize(dir: File): Long {
        var size = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                size += if (file.isDirectory) getDirectorySize(file) else file.length()
            }
        }
        return size
    }
}