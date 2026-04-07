package com.example.ytdownloader.service

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.ytdownloader.YtDownloaderApp
import com.example.ytdownloader.engine.PythonYtDlpEngine
import com.example.ytdownloader.model.DownloadProgress
import com.example.ytdownloader.model.FormatItem
import com.example.ytdownloader.model.PlaylistItem
import com.example.ytdownloader.storage.DownloadsRepository
import com.example.ytdownloader.storage.StoragePrefs
import com.example.ytdownloader.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import com.example.ytdownloader.model.DownloadedItem
import com.example.ytdownloader.runtime.PythonProcessRunner

class PlaylistDownloadService : Service() {

    companion object {
        const val ACTION_START = "com.example.ytdownloader.action.START_PLAYLIST_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.ytdownloader.action.CANCEL_PLAYLIST_DOWNLOAD"

        const val ACTION_PROGRESS_BROADCAST = "com.example.ytdownloader.PLAYLIST_PROGRESS"
        const val ACTION_ITEM_STATE_BROADCAST = "com.example.ytdownloader.PLAYLIST_ITEM_STATE"
        const val ACTION_QUEUE_STATE_BROADCAST = "com.example.ytdownloader.PLAYLIST_QUEUE_STATE"
        const val ACTION_ITEM_COMPLETED_FOR_MAIN =
            "com.example.ytdownloader.PLAYLIST_ITEM_COMPLETED_FOR_MAIN"

        const val EXTRA_PLAYLIST_TITLE = "extra_playlist_title"
        const val EXTRA_PLAYLIST_URL = "extra_playlist_url"
        const val EXTRA_ITEMS_JSON = "extra_items_json"
        const val EXTRA_MODE = "extra_mode"

        const val EXTRA_ITEM_INDEX = "extra_item_index"
        const val EXTRA_ITEM_TITLE = "extra_item_title"
        const val EXTRA_ITEM_URI = "extra_item_uri"
        const val EXTRA_ITEM_MESSAGE = "extra_item_message"

        const val STATE_ITEM_STARTED = "item_started"
        const val STATE_ITEM_COMPLETE = "item_complete"
        const val STATE_ITEM_ERROR = "item_error"
        const val STATE_ITEM_CANCELLED = "item_cancelled"

        const val STATE_QUEUE_STARTED = "queue_started"
        const val STATE_QUEUE_COMPLETE = "queue_complete"
        const val STATE_QUEUE_CANCELLED = "queue_cancelled"
        const val STATE_QUEUE_ERROR = "queue_error"

        const val NOTIFICATION_ID = 2002

        // Сколько раз повторять при сетевой ошибке
        private const val MAX_NETWORK_RETRIES = 5

        // Ждать сеть максимум 10 минут (120 проверок по 5 сек)
        private const val MAX_WAIT_FOR_NETWORK_ITERATIONS = 120
        private const val NETWORK_CHECK_INTERVAL_MS = 5_000L

        // Пауза перед retry после восстановления сети
        private const val RETRY_DELAY_MS = 3_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var logger: FileLogger
    private lateinit var engine: PythonYtDlpEngine
    private lateinit var storagePrefs: StoragePrefs
    private lateinit var downloadsRepository: DownloadsRepository
    private lateinit var connectivityManager: ConnectivityManager

    private var queueJob: Job? = null

    @Volatile
    private var cancelled = false

    override fun onCreate() {
        super.onCreate()
        logger = FileLogger(applicationContext)
        engine = PythonYtDlpEngine(applicationContext, logger)
        storagePrefs = StoragePrefs(applicationContext)
        downloadsRepository = DownloadsRepository(applicationContext)
        connectivityManager =
            getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startQueue(intent)
            ACTION_CANCEL -> cancelQueue()
        }
        return START_NOT_STICKY
    }

    // ─── Запуск очереди ────────────────────────────────────────────────────────

    private fun startQueue(intent: Intent) {
        if (queueJob?.isActive == true) {
            logger.log("PlaylistDownloadService: already active, ignoring duplicate start")
            return
        }

        val playlistTitle = intent.getStringExtra(EXTRA_PLAYLIST_TITLE).orEmpty()
        val itemsJson = intent.getStringExtra(EXTRA_ITEMS_JSON).orEmpty()
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()

        if (itemsJson.isBlank()) {
            stopSelf()
            return
        }

        val items = parseItems(itemsJson)
        if (items.isEmpty()) {
            stopSelf()
            return
        }

        cancelled = false
        updateNotification(playlistTitle, 0, items.size, "Preparing...")
        broadcastQueueState(STATE_QUEUE_STARTED, playlistTitle, 0, items.size)

        queueJob = scope.launch {
            processQueue(items, playlistTitle, mode)
        }
    }

    // ─── Основной цикл ─────────────────────────────────────────────────────────

    private suspend fun processQueue(
        items: List<PlaylistItem>,
        playlistTitle: String,
        mode: String
    ) {
        val total = items.size
        var completed = 0

        try {
            for ((loopIndex, item) in items.withIndex()) {
                if (cancelled || !scope.isActive) {
                    broadcastItemState(
                        STATE_ITEM_CANCELLED, item.index, item.title, null, "Cancelled"
                    )
                    broadcastQueueState(
                        STATE_QUEUE_CANCELLED, playlistTitle, completed, total
                    )
                    finishService()
                    return
                }

                logger.log("Queue: ${loopIndex + 1}/$total '${item.title}'")
                updateNotification(playlistTitle, loopIndex + 1, total, item.title)
                broadcastItemState(STATE_ITEM_STARTED, item.index, item.title, null, null)
                broadcastQueueState(STATE_QUEUE_STARTED, playlistTitle, completed, total)

                val outputDir = getTempDownloadDir()
                val safeName = sanitize(item.title)

                // Retry-цикл для одного элемента
                val result = downloadWithNetworkRetry(
                    item = item,
                    fileName = safeName,
                    mode = mode,
                    outputDir = outputDir,
                    playlistTitle = playlistTitle,
                    completed = completed,
                    total = total
                )

                if (cancelled || !scope.isActive) {
                    broadcastItemState(
                        STATE_ITEM_CANCELLED, item.index, item.title, null, "Cancelled"
                    )
                    broadcastQueueState(
                        STATE_QUEUE_CANCELLED, playlistTitle, completed, total
                    )
                    finishService()
                    return
                }

                if (result.success) {
                    completed++
                    broadcastItemState(
                        STATE_ITEM_COMPLETE, item.index, item.title, result.uriString, null
                    )
                    sendBroadcast(Intent(ACTION_ITEM_COMPLETED_FOR_MAIN).apply {
                        setPackage(packageName)
                    })
                } else {
                    broadcastItemState(
                        STATE_ITEM_ERROR, item.index, item.title, null, result.message
                    )
                }

                broadcastQueueState(STATE_QUEUE_STARTED, playlistTitle, completed, total)
            }

            logger.log("Queue complete: $completed/$total")
            broadcastQueueState(STATE_QUEUE_COMPLETE, playlistTitle, completed, total)

        } catch (e: Exception) {
            logger.log("Queue fatal error: ${e.message}")
            broadcastQueueState(
                STATE_QUEUE_ERROR, playlistTitle, completed, total,
                e.message ?: "Playlist download failed"
            )
        } finally {
            finishService()
        }
    }

    // ─── Retry при сетевой ошибке ──────────────────────────────────────────────

    /**
     * Скачивает один элемент с retry при сетевом таймауте/обрыве.
     *
     * Логика:
     * 1. Пробуем скачать
     * 2. Если NetworkTimeoutException — ждём восстановления сети
     * 3. После восстановления — повторяем (до MAX_NETWORK_RETRIES раз)
     * 4. При отмене — выходим немедленно
     * 5. Другие ошибки (не сетевые) — сразу FAILED без retry
     */
    private suspend fun downloadWithNetworkRetry(
        item: PlaylistItem,
        fileName: String,
        mode: String,
        outputDir: String,
        playlistTitle: String,
        completed: Int,
        total: Int
    ): ItemResult {
        var attempt = 0

        while (attempt <= MAX_NETWORK_RETRIES) {
            if (cancelled) return ItemResult(false, message = "Cancelled")

            val result = downloadSinglePlaylistItem(
                url = item.url,
                fileName = fileName,
                mode = mode,
                outputDir = outputDir
            )

            // Успех или не-сетевая ошибка — возвращаем как есть
            if (result.success) return result
            if (!result.isNetworkError) return result

            // Сетевая ошибка — ждём интернет
            attempt++
            if (attempt > MAX_NETWORK_RETRIES) {
                logger.log("Max retries ($MAX_NETWORK_RETRIES) exceeded for '${item.title}'")
                return ItemResult(
                    false,
                    message = "Failed after $MAX_NETWORK_RETRIES retries: ${result.message}"
                )
            }

            logger.log(
                "Network error for '${item.title}', attempt $attempt/$MAX_NETWORK_RETRIES. " +
                        "Waiting for network..."
            )

            // Уведомляем UI что ждём сеть
            broadcastItemState(
                STATE_ITEM_STARTED,
                item.index,
                item.title,
                null,
                "Waiting for network... (attempt $attempt/$MAX_NETWORK_RETRIES)"
            )

            // Обновляем уведомление
            updateNotification(
                playlistTitle,
                completed + 1,
                total,
                "Waiting for network: ${item.title}"
            )

            // Ждём пока появится интернет (или отмена)
            val networkRestored = waitForNetwork()
            if (!networkRestored) {
                // Отмена во время ожидания
                return ItemResult(false, message = "Cancelled while waiting for network")
            }

            // Небольшая пауза после восстановления сети
            delay(RETRY_DELAY_MS)

            logger.log("Network restored, retrying '${item.title}'")
            updateNotification(
                playlistTitle, completed + 1, total, "Retrying: ${item.title}"
            )
        }

        return ItemResult(false, message = "Network retry limit exceeded")
    }

    /**
     * Ждёт появления интернета.
     * Возвращает true если сеть появилась, false если была отмена.
     */
    private suspend fun waitForNetwork(): Boolean {
        repeat(MAX_WAIT_FOR_NETWORK_ITERATIONS) {
            if (cancelled) return false
            if (isNetworkAvailable()) return true
            delay(NETWORK_CHECK_INTERVAL_MS)
        }
        return isNetworkAvailable()
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }

    // ─── Скачивание одного элемента ────────────────────────────────────────────

    private data class ItemResult(
        val success: Boolean,
        val uriString: String? = null,
        val message: String? = null,
        // true если ошибка сетевая (нужен retry), false если другая (FAILED сразу)
        val isNetworkError: Boolean = false
    )

    private suspend fun downloadSinglePlaylistItem(
        url: String,
        fileName: String,
        mode: String,
        outputDir: String
    ): ItemResult {
        return try {
            when (mode) {
                DownloadForegroundService.MODE_AUDIO -> {
                    val formats = engine.fetchVideoInfo(url).getOrThrow().formats
                    engine.downloadAudio(
                        url = url,
                        availableFormats = formats,
                        outputDir = outputDir,
                        fileName = fileName
                    ).collect { progress ->
                        if (!cancelled) broadcastProgress(progress)
                    }
                }

                else -> {
                    downloadVideoByMode(url, mode, outputDir, fileName)
                }
            }

            if (cancelled) return ItemResult(false, message = "Cancelled")

            val downloadedPath = engine.getLastDownloadedFile()
                ?: return ItemResult(false, message = "Downloaded file missing")

            val file = File(downloadedPath)
            if (!file.exists()) {
                return ItemResult(false, message = "File not found: $downloadedPath")
            }

            val savedUri = saveToPreferredLocation(file, fileName)
                ?: return ItemResult(false, message = "Failed to save file")

            runCatching { file.delete() }
            cleanupTempDownloads()

            ItemResult(true, uriString = savedUri.toString())

        } catch (e: PythonProcessRunner.ProcessCancelledException) {
            cleanupTempDownloads()
            ItemResult(false, message = "Cancelled")

        } catch (e: PythonProcessRunner.NetworkTimeoutException) {
            // Сетевой таймаут — нужен retry
            cleanupTempDownloads()
            logger.log("Network timeout for '$fileName': ${e.message}")
            ItemResult(
                success = false,
                message = e.message,
                isNetworkError = true
            )

        } catch (e: Exception) {
            cleanupTempDownloads()
            // Проверяем является ли обычная Exception сетевой по тексту сообщения
            val isNetwork = isNetworkRelatedError(e.message ?: "")
            logger.log("Download error for '$fileName' (network=$isNetwork): ${e.message}")
            ItemResult(
                success = false,
                message = e.message ?: "Download failed",
                isNetworkError = isNetwork
            )
        }
    }

    /**
     * Определяет по тексту ошибки — сетевая ли она.
     * yt-dlp пишет эти строки при обрыве соединения.
     */
    private fun isNetworkRelatedError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("connection reset") ||
                lower.contains("connection refused") ||
                lower.contains("network is unreachable") ||
                lower.contains("etimedout") ||
                lower.contains("timed out") ||
                lower.contains("no route to host") ||
                lower.contains("socket timeout") ||
                lower.contains("read timeout") ||
                lower.contains("connect timeout") ||
                lower.contains("transporterror") ||
                lower.contains("remotedisconnected") ||
                lower.contains("chunked encoding") ||
                lower.contains("incomplete read") ||
                lower.contains("network error") ||
                lower.contains("ssl") && lower.contains("error") ||
                lower.contains("unable to connect") ||
                lower.contains("name or service not known") ||
                lower.contains("temporary failure in name resolution")
    }

    private suspend fun downloadVideoByMode(
        url: String,
        mode: String,
        outputDir: String,
        fileName: String
    ) {
        val videoInfo = engine.fetchVideoInfo(url).getOrThrow()
        val formats = videoInfo.formats

        val selectedFormat = when (mode) {
            DownloadForegroundService.MODE_1080 ->
                findBestVideoUpTo(formats, 1080) ?: findBestVideoFormat(formats)
                ?: throw IllegalStateException("No suitable video format found")

            DownloadForegroundService.MODE_1440 ->
                findBestVideoUpTo(formats, 1440) ?: findBestVideoFormat(formats)
                ?: throw IllegalStateException("No suitable video format found")

            DownloadForegroundService.MODE_2160 ->
                findBestVideoUpTo(formats, 2160) ?: findBestVideoFormat(formats)
                ?: throw IllegalStateException("No suitable video format found")

            else ->
                findBestVideoFormat(formats)
                    ?: throw IllegalStateException("No suitable video format found")
        }

        engine.downloadVideo(
            url = url,
            selectedFormat = selectedFormat,
            availableFormats = formats,
            outputDir = outputDir,
            fileName = fileName
        ).collect { progress ->
            if (!cancelled) broadcastProgress(progress)
        }
    }

    // ─── Format selection ──────────────────────────────────────────────────────

    private fun findBestVideoFormat(formats: List<FormatItem>): FormatItem? =
        formats.filter { it.hasVideo }.maxWithOrNull(
            compareBy<FormatItem> { it.height ?: 0 }
                .thenBy { it.fps ?: 0 }
                .thenBy { it.tbr ?: 0f }
        )

    private fun findBestVideoUpTo(formats: List<FormatItem>, maxHeight: Int): FormatItem? =
        formats.filter { it.hasVideo && (it.height ?: 0) <= maxHeight }.maxWithOrNull(
            compareBy<FormatItem> { it.height ?: 0 }
                .thenBy { it.fps ?: 0 }
                .thenBy { it.tbr ?: 0f }
        )

    // ─── Notification ──────────────────────────────────────────────────────────

    private fun updateNotification(
        playlistTitle: String,
        current: Int,
        total: Int,
        itemTitle: String
    ) {
        val notification = NotificationCompat.Builder(this, YtDownloaderApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                if (current > 0) "Downloading $current/$total • $playlistTitle"
                else "Preparing: $playlistTitle"
            )
            .setContentText(itemTitle.take(60))
            .setProgress(total, current, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (current == 0) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─── Parse ─────────────────────────────────────────────────────────────────

    private fun parseItems(json: String): List<PlaylistItem> {
        return try {
            val gson = com.google.gson.Gson()
            val type =
                object : com.google.gson.reflect.TypeToken<List<PlaylistItem>>() {}.type
            gson.fromJson<List<PlaylistItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            logger.log("parseItems error: ${e.message}")
            emptyList()
        }
    }

    // ─── Save file ─────────────────────────────────────────────────────────────

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

            root.findFile(displayName)?.delete()
            val outFile =
                root.createFile(mimeType, displayName.removeSuffix(".$ext")) ?: return null

            contentResolver.openOutputStream(outFile.uri)?.use { output ->
                FileInputStream(file).use { it.copyTo(output) }
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
            logger.log("saveToPickedFolder error: ${e.message}")
            null
        }
    }

    private fun saveToMediaCollections(file: File, desiredName: String): Uri? {
        return try {
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                val collection = when {
                    isVideo -> MediaStore.Video.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                    isAudio -> MediaStore.Audio.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                val uri = contentResolver.insert(collection, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(file).use { it.copyTo(output) }
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
                    isVideo -> Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES
                    )
                    isAudio -> Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC
                    )
                    else -> Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                }
                if (!targetDir.exists()) targetDir.mkdirs()
                val destFile = File(targetDir, finalName)
                file.copyTo(destFile, overwrite = true)
                val uri = Uri.fromFile(destFile)
                downloadsRepository.add(
                    DownloadedItem(
                        title = desiredName,
                        uriString = uri.toString(),
                        mimeType = mimeType,
                        fileName = destFile.name,
                        timestamp = System.currentTimeMillis()
                    )
                )
                uri
            }
        } catch (e: Exception) {
            logger.log("saveToMediaCollections error: ${e.message}")
            null
        }
    }

    private fun detectMimeType(file: File): String = when {
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

    private fun cleanupTempDownloads() {
        runCatching {
            File(cacheDir, "downloads").listFiles()?.forEach {
                runCatching { it.delete() }
            }
        }
    }

    private fun getTempDownloadDir(): String {
        val dir = File(cacheDir, "downloads")
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(200)

    // ─── Broadcasts ────────────────────────────────────────────────────────────

    private fun broadcastProgress(progress: DownloadProgress) {
        sendBroadcast(Intent(ACTION_PROGRESS_BROADCAST).apply {
            setPackage(packageName)
            putExtra("percent", progress.percent)
            putExtra("downloadedBytes", progress.downloadedBytes)
            putExtra("totalBytes", progress.totalBytes)
            putExtra("speed", progress.speed)
            putExtra("eta", progress.eta)
            putExtra("status", progress.status)
            putExtra("phase", progress.phase.name)
        })
    }

    private fun broadcastItemState(
        state: String,
        index: Int,
        title: String,
        uri: String?,
        message: String?
    ) {
        sendBroadcast(Intent(ACTION_ITEM_STATE_BROADCAST).apply {
            setPackage(packageName)
            putExtra("state", state)
            putExtra(EXTRA_ITEM_INDEX, index)
            putExtra(EXTRA_ITEM_TITLE, title)
            putExtra(EXTRA_ITEM_URI, uri)
            putExtra(EXTRA_ITEM_MESSAGE, message)
        })
    }

    private fun broadcastQueueState(
        state: String,
        playlistTitle: String,
        completed: Int,
        total: Int,
        message: String? = null
    ) {
        sendBroadcast(Intent(ACTION_QUEUE_STATE_BROADCAST).apply {
            setPackage(packageName)
            putExtra("state", state)
            putExtra("playlistTitle", playlistTitle)
            putExtra("completed", completed)
            putExtra("total", total)
            putExtra("message", message)
        })
    }

    // ─── Cancel ────────────────────────────────────────────────────────────────

    private fun cancelQueue() {
        logger.log("PlaylistDownloadService: cancelQueue()")
        cancelled = true
        runCatching { engine.cancel() }
        queueJob?.cancel()
        finishService()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled = true
        queueJob?.cancel()
        scope.cancel()
        logger.log("PlaylistDownloadService destroyed")
    }
}