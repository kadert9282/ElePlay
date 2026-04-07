package com.example.ytdownloader.updater

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpdateAvailable(
        val latestVersion: String,
        val currentVersion: String,
        val downloadUrl: String
    ) : UpdateState()
    data class ReleasesLoaded(
        val releases: List<YtDlpRelease>
    ) : UpdateState()
    data object UpToDate : UpdateState()
    data class Downloading(val percent: Int) : UpdateState()
    data object Installing : UpdateState()
    data object Success : UpdateState()
    data class Error(val message: String) : UpdateState()
}

data class YtDlpRelease(
    val version: String,
    val downloadUrl: String,
    val isLatest: Boolean
)