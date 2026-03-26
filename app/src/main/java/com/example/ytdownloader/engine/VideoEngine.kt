package com.example.ytdownloader.engine

import com.example.ytdownloader.model.DownloadProgress
import com.example.ytdownloader.model.DownloadResult
import com.example.ytdownloader.model.FormatItem
import com.example.ytdownloader.model.VideoInfo
import kotlinx.coroutines.flow.Flow

interface VideoEngine {

    suspend fun isRuntimeReady(): Boolean

    fun initializeRuntime(): Flow<String>

    suspend fun fetchVideoInfo(url: String): Result<VideoInfo>

    fun downloadVideo(
        url: String,
        selectedFormat: FormatItem,
        availableFormats: List<FormatItem>,
        outputDir: String,
        fileName: String? = null
    ): Flow<DownloadProgress>

    fun downloadAudio(
        url: String,
        availableFormats: List<FormatItem>,
        outputDir: String,
        fileName: String? = null
    ): Flow<DownloadProgress>

    fun cancel()

    fun getLastResult(): DownloadResult?

    fun getLastDownloadedFile(): String?
}