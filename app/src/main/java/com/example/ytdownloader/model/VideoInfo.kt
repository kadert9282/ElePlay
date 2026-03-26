package com.example.ytdownloader.model

data class VideoInfo(
    val title: String,
    val duration: Int?,
    val thumbnail: String?,
    val uploader: String?,
    val description: String?,
    val formats: List<FormatItem>,
    val url: String
) {
    val durationFormatted: String
        get() {
            val d = duration ?: return "Unknown"
            val hours = d / 3600
            val minutes = (d % 3600) / 60
            val seconds = d % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
}