package com.example.ytdownloader.model

data class PlaylistInfo(
    val title: String,
    val uploader: String?,
    val entries: List<PlaylistItem>
)