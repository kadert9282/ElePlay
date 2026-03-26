package com.example.ytdownloader.service

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.ytdownloader.engine.PythonYtDlpEngine
import com.example.ytdownloader.engine.VideoEngine
import com.example.ytdownloader.model.DownloadProgress
import com.example.ytdownloader.model.DownloadedItem
import com.example.ytdownloader.model.FormatItem
import com.example.ytdownloader.storage.DownloadsRepository
import com.example.ytdownloader.storage.StoragePrefs
import com.example.ytdownloader.util.DownloadNotificationHelper
import com.example.ytdownloader.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

class DownloadForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.example.ytdownloader.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.ytdownloader.action.CANCEL_DOWNLOAD"

        const val ACTION_PROGRESS_BROADCAST = "com.example.ytdownloader.DOWNLOAD_PROGRESS"
        const val ACTION_STATE_BROADCAST = "com.example.ytdownloader.DOWNLOAD_STATE"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_FORMAT_ID = "extra_format_id"
        const val EXTRA_FORMAT_HEIGHT = "extra_format_height"
        const val EXTRA_FORMAT_WIDTH = "extra_format_width"
        const val EXTRA_FORMAT_EXT = "extra_format_ext"
        const val EXTRA_FORMAT_RESOLUTION = "extra_format_resolution"
        const val EXTRA_FORMAT_FILESIZE = "extra_format_filesize"
        const val EXTRA_FORMAT_FPS = "extra_format_fps"
        const val EXTRA_FORMAT_VCODEC = "extra_format_vcodec"
        const val EXTRA_FORMAT_ACODEC = "extra_format_acodec"
        const val EXTRA_FORMAT_ABR = "extra_format_abr"
        const val EXTRA_FORMAT_TBR = "extra_format_tbr"
        const val EXTRA_FORMAT_NOTE = "extra_format_note"
        const val EXTRA_HAS_VIDEO = "extra_has_video"
        const val EXTRA_HAS_AUDIO = "extra_has_audio"
        const val EXTRA_TITLE = "extra_title"

        const val MODE_SELECTED = "selected"
        const val MODE_BEST = "best"
        const val MODE_1080 = "1080"
        const val MODE_1440 = "1440"
        const val MODE_2160 = "2160"
        const val MODE_AUDIO = "audio"

        const val STATE_IDLE = "idle"
        const val STATE_RUNNING = "running"
        const val STATE_COMPLETE = "complete"
        const val STATE_ERROR = "error"
        const val STATE_CANCELLED = "cancelled"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentProgress: DownloadProgress? = null
            private set

        @Volatile
        var currentTitle: String? = null
            private set

        @Volatile
        var currentState: String = STATE_IDLE
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private lateinit var logger: FileLogger
    private lateinit var engine: VideoEngine
    private lateinit var storagePrefs: StoragePrefs
    private lateinit var downloadsRepository: DownloadsRepository
    private lateinit var notificationHelper: DownloadNotificationHelper

    private var downloadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        logger = FileLogger(applicationContext)
        engine = PythonYtDlpEngine(applicationContext, logger)
        storagePrefs = StoragePrefs(applicationContext)
        downloadsRepository = DownloadsRepository(applicationContext)
        notificationHelper = DownloadNotificationHelper(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDownload(intent)
            ACTION_CANCEL -> cancelDownload()
        }
        return START_STICKY
    }

    private fun startDownload(intent: Intent) {
        if (downloadJob?.isActive == true) {
            logger.log("Download already running, ignoring start request")
            return
        }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "video"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: fileName

        if (url.isBlank()) {
            handleError("URL is empty")
            return
        }

        currentTitle = title
        currentState = STATE_RUNNING
        isRunning = true
        currentProgress = DownloadProgress(status = "Starting...")

        startForeground(
            DownloadNotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildForegroundNotification(title, "Starting...", 0, true)
        )

        broadcastState(STATE_RUNNING, "Starting...")
        broadcastProgress(currentProgress!!)

        downloadJob = serviceScope.launch {
            try {
                val tempDir = getTempDownloadDir()

                when (mode) {
                    MODE_SELECTED -> {
                        val selectedFormat = readFormatFromIntent(intent)
                            ?: throw IllegalArgumentException("Selected format is missing")

                        val availableFormats = fetchFormats(url)
                        engine.downloadVideo(
                            url = url,
                            selectedFormat = selectedFormat,
                            availableFormats = availableFormats,
                            outputDir = tempDir,
                            fileName = fileName
                        ).collect { progress ->
                            handleProgress(progress, fileName)
                        }
                    }

                    MODE_BEST -> {
                        val formats = fetchFormats(url)
                        val best = findBestVideoFormat(formats)
                            ?: throw IllegalStateException("No video formats found")

                        engine.downloadVideo(
                            url = url,
                            selectedFormat = best,
                            availableFormats = formats,
                            outputDir = tempDir,
                            fileName = fileName
                        ).collect { progress ->
                            handleProgress(progress, fileName)
                        }
                    }

                    MODE_1080 -> {
                        val formats = fetchFormats(url)
                        val target = findBestVideoUpTo(formats, 1080)
                            ?: throw IllegalStateException("No video formats up to 1080p found")

                        engine.downloadVideo(
                            url = url,
                            selectedFormat = target,
                            availableFormats = formats,
                            outputDir = tempDir,
                            fileName = fileName
                        ).collect { progress ->
                            handleProgress(progress, fileName)
                        }
                    }

                    MODE_1440 -> {
                        val formats = fetchFormats(url)
                        val target = findBestVideoUpTo(formats, 1440)
                            ?: throw IllegalStateException("No video formats up to 1440p found")

                        engine.downloadVideo(
                            url = url,
                            selectedFormat = target,
                            availableFormats = formats,
                            outputDir = tempDir,
                            fileName = fileName
                        ).collect { progress ->
                            handleProgress(progress, fileName)
                        }
                    }

                    MODE_2160 -> {
                        val formats = fetchFormats(url)
                        val target = findBestVideoUpTo(formats, 2160)
                            ?: throw IllegalStateException("No video formats up to 2160p found")

                        engine.downloadVideo(
                            url = url,
                            selectedFormat = target,
                            availableFormats = formats,
                            outputDir = tempDir,
                            fileName = fileName
                        ).collect { progress ->
                            handleProgress(progress, fileName)
                        }
                    }

                    MODE_AUDIO -> {
                        val formats = fetchFormats(url)
                        engine.downloadAudio(
                            url = url,
                            availableFormats = formats,
                            outputDir = tempDir,
                            fileName = fileName
                        ).collect { progress ->
                            handleProgress(progress, fileName)
                        }
                    }

                    else -> {
                        throw IllegalArgumentException("Unknown download mode: $mode")
                    }
                }
            } catch (e: Exception) {
                handleError(e.message ?: "Unexpected service error")
            }
        }
    }

    private fun handleProgress(progress: DownloadProgress, desiredName: String) {
        currentProgress = progress
        broadcastProgress(progress)

        notificationHelper.updateForegroundProgress(
            title = currentTitle ?: "Downloading",
            text = progress.status.ifBlank { "Downloading..." },
            progress = progress.percent.toInt(),
            indeterminate = progress.percent <= 0f
        )

        when (progress.phase) {
            DownloadProgress.Phase.COMPLETE -> {
                isRunning = false
                currentState = STATE_COMPLETE
                handleDownloadComplete(desiredName)
                broadcastState(STATE_COMPLETE, "Complete")
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }

            DownloadProgress.Phase.ERROR -> {
                isRunning = false
                currentState = STATE_ERROR
                broadcastState(STATE_ERROR, progress.status)
                notificationHelper.showComplete("Download failed: ${progress.status}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            DownloadProgress.Phase.CANCELLED -> {
                isRunning = false
                currentState = STATE_CANCELLED
                broadcastState(STATE_CANCELLED, "Cancelled")
                notificationHelper.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> Unit
        }
    }

    private fun handleError(message: String) {
        logger.log("Service error: $message")
        isRunning = false
        currentState = STATE_ERROR
        currentProgress = DownloadProgress(
            status = message,
            phase = DownloadProgress.Phase.ERROR
        )
        broadcastProgress(currentProgress!!)
        broadcastState(STATE_ERROR, message)
        notificationHelper.showComplete("Download failed: $message")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelDownload() {
        logger.log("Service cancel requested")
        engine.cancel()
        downloadJob?.cancel()
        isRunning = false
        currentState = STATE_CANCELLED
        currentProgress = DownloadProgress(
            status = "Cancelled",
            phase = DownloadProgress.Phase.CANCELLED
        )
        broadcastProgress(currentProgress!!)
        broadcastState(STATE_CANCELLED, "Cancelled")
        notificationHelper.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun fetchFormats(url: String): List<FormatItem> {
        val result = engine.fetchVideoInfo(url)
        return result.getOrElse { throw it }.formats
    }

    private fun readFormatFromIntent(intent: Intent): FormatItem? {
        val formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: return null
        val ext = intent.getStringExtra(EXTRA_FORMAT_EXT) ?: "mp4"
        val resolution = intent.getStringExtra(EXTRA_FORMAT_RESOLUTION) ?: "unknown"

        return FormatItem(
            formatId = formatId,
            ext = ext,
            resolution = resolution,
            filesize = intent.getLongExtra(EXTRA_FORMAT_FILESIZE, -1L).takeIf { it >= 0 },
            fps = intent.getIntExtra(EXTRA_FORMAT_FPS, -1).takeIf { it >= 0 },
            vcodec = intent.getStringExtra(EXTRA_FORMAT_VCODEC),
            acodec = intent.getStringExtra(EXTRA_FORMAT_ACODEC),
            abr = intent.getFloatExtra(EXTRA_FORMAT_ABR, -1f).takeIf { it >= 0f },
            tbr = intent.getFloatExtra(EXTRA_FORMAT_TBR, -1f).takeIf { it >= 0f },
            formatNote = intent.getStringExtra(EXTRA_FORMAT_NOTE),
            hasVideo = intent.getBooleanExtra(EXTRA_HAS_VIDEO, false),
            hasAudio = intent.getBooleanExtra(EXTRA_HAS_AUDIO, false),
            height = intent.getIntExtra(EXTRA_FORMAT_HEIGHT, -1).takeIf { it >= 0 },
            width = intent.getIntExtra(EXTRA_FORMAT_WIDTH, -1).takeIf { it >= 0 }
        )
    }

    private fun findBestVideoFormat(formats: List<FormatItem>): FormatItem? {
        return formats
            .filter { it.hasVideo }
            .maxWithOrNull(
                compareBy<FormatItem> { it.height ?: 0 }
                    .thenBy { it.fps ?: 0 }
                    .thenBy { it.tbr ?: 0f }
            )
    }

    private fun findBestVideoUpTo(formats: List<FormatItem>, maxHeight: Int): FormatItem? {
        return formats
            .filter { it.hasVideo && (it.height ?: 0) <= maxHeight }
            .maxWithOrNull(
                compareBy<FormatItem> { it.height ?: 0 }
                    .thenBy { it.fps ?: 0 }
                    .thenBy { it.tbr ?: 0f }
            )
    }

    private fun handleDownloadComplete(desiredName: String) {
        val lastFile = engine.getLastDownloadedFile()
        if (lastFile != null) {
            val file = File(lastFile)
            if (file.exists()) {
                val savedUri = saveToPreferredLocation(file, desiredName)
                if (savedUri != null) {
                    notificationHelper.showComplete("Saved: ${file.name}")
                    logger.log("File saved: $savedUri")
                    file.delete()
                } else {
                    notificationHelper.showComplete("Downloaded: ${file.name}")
                }
            }
        }
    }

    private fun saveToPreferredLocation(file: File, desiredName: String): Uri? {
        val customFolderUri = storagePrefs.getSaveTreeUri()
        return if (customFolderUri != null) {
            saveToPickedFolder(file, desiredName, customFolderUri)
                ?: saveToMediaCollections(file, desiredName)
        } else {
            saveToMediaCollections(file, desiredName)
        }
    }

    private fun saveToPickedFolder(file: File, desiredName: String, treeUri: Uri): Uri? {
        return try {
            val root = DocumentFile.fromTreeUri(this, treeUri) ?: return null

            val mimeType = detectMimeType(file)
            val ext = file.extension.ifEmpty { "mp4" }
            val displayName = "$desiredName.$ext"

            val existing = root.findFile(displayName)
            existing?.delete()

            val outFile = root.createFile(mimeType, displayName.removeSuffix(".$ext")) ?: return null

            contentResolver.openOutputStream(outFile.uri)?.use { output ->
                FileInputStream(file).use { input ->
                    input.copyTo(output)
                }
            }

            downloadsRepository.add(
                DownloadedItem(
                    title = desiredName,
                    uriString = outFile.uri.toString(),
                    mimeType = mimeType,
                    fileName = displayName,
                    timestamp = System.currentTimeMillis()
                )
            )

            outFile.uri
        } catch (e: Exception) {
            logger.log("Failed to save to picked folder: ${e.message}")
            null
        }
    }

    private fun saveToMediaCollections(file: File, desiredName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val isVideo = file.name.endsWith(".mp4", true) ||
                        file.name.endsWith(".mkv", true) ||
                        file.name.endsWith(".webm", true)

                val isAudio = file.name.endsWith(".m4a", true) ||
                        file.name.endsWith(".mp3", true) ||
                        file.name.endsWith(".opus", true) ||
                        file.name.endsWith(".ogg", true) ||
                        file.name.endsWith(".wav", true)

                val mimeType = detectMimeType(file)
                val ext = file.extension.ifEmpty { if (isAudio) "m4a" else "mp4" }
                val finalName = "$desiredName.$ext"

                val relativePath = when {
                    isVideo -> Environment.DIRECTORY_MOVIES
                    isAudio -> Environment.DIRECTORY_MUSIC
                    else -> Environment.DIRECTORY_DOWNLOADS
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }

                val resolver = contentResolver

                val collection = when {
                    isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    isAudio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }

                val uri = resolver.insert(collection, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(file).use { input ->
                            input.copyTo(output)
                        }
                    }

                    downloadsRepository.add(
                        DownloadedItem(
                            title = desiredName,
                            uriString = uri.toString(),
                            mimeType = mimeType,
                            fileName = finalName,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                uri
            } else {
                val targetDir = when {
                    file.name.endsWith(".mp4", true) ||
                            file.name.endsWith(".mkv", true) ||
                            file.name.endsWith(".webm", true) ->
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)

                    file.name.endsWith(".m4a", true) ||
                            file.name.endsWith(".mp3", true) ||
                            file.name.endsWith(".opus", true) ||
                            file.name.endsWith(".ogg", true) ||
                            file.name.endsWith(".wav", true) ->
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

                    else ->
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }

                if (!targetDir.exists()) targetDir.mkdirs()

                val destFile = File(targetDir, "${desiredName}.${file.extension}")
                file.copyTo(destFile, overwrite = true)

                val uri = Uri.fromFile(destFile)
                downloadsRepository.add(
                    DownloadedItem(
                        title = desiredName,
                        uriString = uri.toString(),
                        mimeType = detectMimeType(file),
                        fileName = destFile.name,
                        timestamp = System.currentTimeMillis()
                    )
                )
                uri
            }
        } catch (e: Exception) {
            logger.log("Failed to save media: ${e.message}")
            null
        }
    }

    private fun detectMimeType(file: File): String {
        return when {
            file.name.endsWith(".mp4", true) -> "video/mp4"
            file.name.endsWith(".mkv", true) -> "video/x-matroska"
            file.name.endsWith(".webm", true) -> "video/webm"
            file.name.endsWith(".m4a", true) -> "audio/mp4"
            file.name.endsWith(".mp3", true) -> "audio/mpeg"
            file.name.endsWith(".opus", true) -> "audio/opus"
            file.name.endsWith(".ogg", true) -> "audio/ogg"
            file.name.endsWith(".wav", true) -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun getTempDownloadDir(): String {
        val dir = File(cacheDir, "downloads")
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun broadcastProgress(progress: DownloadProgress) {
        val intent = Intent(ACTION_PROGRESS_BROADCAST).apply {
            setPackage(packageName)
            putExtra("percent", progress.percent)
            putExtra("downloadedBytes", progress.downloadedBytes)
            putExtra("totalBytes", progress.totalBytes)
            putExtra("speed", progress.speed)
            putExtra("eta", progress.eta)
            putExtra("status", progress.status)
            putExtra("phase", progress.phase.name)
        }
        sendBroadcast(intent)
    }

    private fun broadcastState(state: String, message: String) {
        val intent = Intent(ACTION_STATE_BROADCAST).apply {
            setPackage(packageName)
            putExtra("state", state)
            putExtra("message", message)
            putExtra("title", currentTitle ?: "")
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.cancel()
        downloadJob?.cancel()
        serviceScope.cancel()
        isRunning = false
        if (currentState == STATE_RUNNING) {
            currentState = STATE_IDLE
        }
    }
}