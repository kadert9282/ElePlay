package com.example.ytdownloader.model

data class DownloadedItem(
    val title: String,
    val uriString: String,
    val mimeType: String,
    val fileName: String,
    val timestamp: Long
)