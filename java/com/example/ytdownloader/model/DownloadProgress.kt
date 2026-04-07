package com.example.ytdownloader.model

data class DownloadProgress(
    val percent: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speed: String = "",
    val eta: String = "",
    val status: String = "",
    val phase: Phase = Phase.DOWNLOADING
) {
    enum class Phase {
        DOWNLOADING,
        MERGING,
        CONVERTING,
        COMPLETE,
        ERROR,
        CANCELLED
    }
}