package com.example.ytdownloader.model

data class FormatItem(
    val formatId: String,
    val ext: String,
    val resolution: String,
    val filesize: Long?,
    val fps: Int?,
    val vcodec: String?,
    val acodec: String?,
    val abr: Float?,
    val tbr: Float?,
    val formatNote: String?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val height: Int?,
    val width: Int?
) {
    val isVideoOnly: Boolean get() = hasVideo && !hasAudio
    val isAudioOnly: Boolean get() = !hasVideo && hasAudio
    val isMuxed: Boolean get() = hasVideo && hasAudio

    val is4K: Boolean get() = (height ?: 0) >= 2160
    val is1440p: Boolean get() = (height ?: 0) in 1440 until 2160
    val is60Fps: Boolean get() = (fps ?: 0) >= 50
    val isHdrLike: Boolean
        get() {
            val note = (formatNote ?: "").lowercase()
            val vc = (vcodec ?: "").lowercase()
            return "hdr" in note || "hdr" in vc || "hlg" in note || "pq" in note
        }

    val badgeText: String
        get() = buildList {
            if (is4K) add("4K")
            else if (is1440p) add("1440p")
            if (is60Fps) add("60fps")
            if (isHdrLike) add("HDR")
        }.joinToString(" • ")

    val displayName: String
        get() {
            val parts = mutableListOf<String>()
            if (hasVideo) {
                parts.add(resolution)
                fps?.let { if (it > 30) parts.add("${it}fps") }
            }
            if (isAudioOnly) {
                parts.add("Audio")
                abr?.let { parts.add("${it.toInt()}kbps") }
            }
            if (isVideoOnly) parts.add("(video only)")
            ext.let { parts.add(it) }
            filesize?.let { parts.add(formatFileSize(it)) }
            formatNote?.let { if (it.isNotBlank()) parts.add(it) }
            return parts.joinToString(" • ")
        }

    val qualityLabel: String
        get() = when {
            isAudioOnly -> "Audio ${abr?.toInt() ?: "?"}kbps"
            height != null -> "${height}p"
            else -> resolution
        }

    companion object {
        fun formatFileSize(bytes: Long): String {
            return when {
                bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
                bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
                bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}