package com.example.ytdownloader.model

data class PlaylistItem(
    val index: Int,
    val id: String,
    val title: String,
    val duration: Int?,
    val thumbnail: String?,
    val url: String,
    val isSelected: Boolean = true,
    val downloadState: DownloadState = DownloadState.NONE,
    val statusText: String? = null,
    val downloadedUri: String? = null,
    val errorMessage: String? = null
) {
    enum class DownloadState {
        NONE,
        QUEUED,
        DOWNLOADING,
        DONE,
        FAILED,
        SKIPPED
    }

    val durationFormatted: String
        get() {
            val d = duration ?: return "?"
            val h = d / 3600
            val m = (d % 3600) / 60
            val s = d % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%d:%02d".format(m, s)
        }
}