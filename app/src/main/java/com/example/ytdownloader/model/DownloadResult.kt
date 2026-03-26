package com.example.ytdownloader.model

import java.io.File

sealed class DownloadResult {
    data class Success(val file: File, val title: String) : DownloadResult()
    data class Error(val message: String, val errorCode: ErrorCode = ErrorCode.UNKNOWN) : DownloadResult()
    data object Cancelled : DownloadResult()

    enum class ErrorCode {
        RUNTIME_NOT_FOUND,
        PYTHON_ERROR,
        FFMPEG_ERROR,
        NETWORK_ERROR,
        FORBIDDEN,
        AGE_RESTRICTED,
        PRIVATE_VIDEO,
        VIDEO_UNAVAILABLE,
        MERGE_FAILED,
        FILE_WRITE_ERROR,
        INVALID_URL,
        PARSE_ERROR,
        UNKNOWN
    }
}