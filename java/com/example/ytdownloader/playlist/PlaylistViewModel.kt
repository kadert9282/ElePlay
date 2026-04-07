package com.example.ytdownloader.playlist

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.ytdownloader.engine.PythonYtDlpEngine
import com.example.ytdownloader.model.DownloadProgress
import com.example.ytdownloader.model.PlaylistInfo
import com.example.ytdownloader.model.PlaylistItem
import com.example.ytdownloader.service.DownloadForegroundService
import com.example.ytdownloader.service.PlaylistDownloadService
import com.example.ytdownloader.storage.PlaylistStateStorage
import com.example.ytdownloader.storage.StoragePrefs
import com.example.ytdownloader.util.FileLogger
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistViewModel(application: Application) : AndroidViewModel(application) {

    // ─── Вложенные типы ───────────────────────────────────────────────────────

    data class PlaylistQualityMode(
        val serviceMode: String,
        val label: String,
        val height: Int? = null,
        val audioOnly: Boolean = false
    )

    sealed class State {
        data object Idle : State()
        data object Fetching : State()
        data class Loaded(val info: PlaylistInfo) : State()
        data class Error(val message: String) : State()
    }

    // ─── Зависимости ──────────────────────────────────────────────────────────

    private val logger = FileLogger(application)
    private val engine = PythonYtDlpEngine(application, logger)
    private val playlistStateStorage = PlaylistStateStorage(application)
    private val storagePrefs = StoragePrefs(application)
    private val gson = Gson()

    // ─── LiveData ─────────────────────────────────────────────────────────────

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    private val _items = MutableLiveData<List<PlaylistItem>>(emptyList())
    val items: LiveData<List<PlaylistItem>> = _items

    // Общее количество скачанных за всё время (весь плейлист)
    private val _doneCount = MutableLiveData(0)
    val doneCount: LiveData<Int> = _doneCount

    // Количество видео в текущей очереди скачивания
    private val _queueSize = MutableLiveData(0)
    val queueSize: LiveData<Int> = _queueSize

    // Сколько скачано в текущей сессии очереди
    private val _queueDoneCount = MutableLiveData(0)
    val queueDoneCount: LiveData<Int> = _queueDoneCount

    private val _isDownloadingAll = MutableLiveData(false)
    val isDownloadingAll: LiveData<Boolean> = _isDownloadingAll

    private val _summaryText = MutableLiveData("")
    val summaryText: LiveData<String> = _summaryText

    private val _qualityOptions = MutableLiveData<List<PlaylistQualityMode>>(emptyList())
    val qualityOptions: LiveData<List<PlaylistQualityMode>> = _qualityOptions

    private val _selectedQuality = MutableLiveData<PlaylistQualityMode?>(null)
    val selectedQuality: LiveData<PlaylistQualityMode?> = _selectedQuality

    private val _currentDownloadIndex = MutableLiveData(-1)
    val currentDownloadIndex: LiveData<Int> = _currentDownloadIndex

    private val _uiEvent = MutableLiveData<String?>()
    val uiEvent: LiveData<String?> = _uiEvent

    // ─── Внутреннее состояние ─────────────────────────────────────────────────

    private var playlistUrlValue: String = ""

    // ─── Init ─────────────────────────────────────────────────────────────────

    init {
        restoreState()
    }

    // ─── Восстановление состояния ─────────────────────────────────────────────

    private fun restoreState() {
        val info = playlistStateStorage.getPlaylistInfo() ?: return
        val items = playlistStateStorage.getItems()
        val url = playlistStateStorage.getPlaylistUrl() ?: return
        if (items.isEmpty()) return

        playlistUrlValue = url

        // При восстановлении сбрасываем зависшие QUEUED/DOWNLOADING → NONE
        val cleaned = items.map { item ->
            when (item.downloadState) {
                PlaylistItem.DownloadState.QUEUED,
                PlaylistItem.DownloadState.DOWNLOADING -> item.copy(
                    downloadState = PlaylistItem.DownloadState.NONE,
                    statusText = null
                )
                else -> item
            }
        }

        _items.value = cleaned
        _doneCount.value = cleaned.count { it.downloadState == PlaylistItem.DownloadState.DONE }
        _state.value = State.Loaded(info)
        _isDownloadingAll.value = false
        _queueSize.value = 0
        _queueDoneCount.value = 0
        updateSummary(cleaned)

        if (cleaned != items) {
            playlistStateStorage.saveItems(cleaned)
        }
    }

    // ─── Загрузка плейлиста ───────────────────────────────────────────────────

    fun fetchPlaylist(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) return

        if (normalized != playlistUrlValue) {
            clearAllState()
        }

        playlistUrlValue = normalized
        _state.value = State.Fetching

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.fetchPlaylistInfo(normalized)
            }

            result.fold(
                onSuccess = { info ->
                    val context = getApplication<Application>()

                    val items = withContext(Dispatchers.IO) {
                        info.entries.map { entry ->
                            val isDownloaded = isFileAlreadyDownloaded(entry.title, context)
                            entry.copy(
                                isSelected = !isDownloaded,
                                downloadState = if (isDownloaded)
                                    PlaylistItem.DownloadState.DONE
                                else
                                    PlaylistItem.DownloadState.NONE,
                                statusText = if (isDownloaded) "Already downloaded" else null
                            )
                        }
                    }

                    _items.value = items
                    _doneCount.value =
                        items.count { it.downloadState == PlaylistItem.DownloadState.DONE }
                    _state.value = State.Loaded(info)

                    playlistStateStorage.savePlaylistInfo(normalized, info, items)
                    updateSummary(items)
                    loadFormatsForQualityChips(info)
                },
                onFailure = { e ->
                    _state.value = State.Error(e.message ?: "Failed to load playlist")
                    logger.log("fetchPlaylist error: ${e.message}")
                }
            )
        }
    }

    // ─── Проверка файла на диске ──────────────────────────────────────────────

    private fun isFileAlreadyDownloaded(
        title: String,
        context: android.content.Context
    ): Boolean {
        val safeName = sanitizeFileName(title)
        val treeUri = storagePrefs.getSaveTreeUri()

        return if (treeUri != null) {
            checkInPickedFolder(treeUri, safeName, context)
        } else {
            checkInMediaStore(safeName, context)
        }
    }

    private fun checkInPickedFolder(
        treeUri: Uri,
        safeName: String,
        context: android.content.Context
    ): Boolean {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val extensions = listOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "ogg", "wav")
            extensions.any { ext ->
                val file = root.findFile("$safeName.$ext")
                file != null && file.exists() && file.length() > 0
            }
        } catch (e: Exception) {
            logger.log("checkInPickedFolder error: ${e.message}")
            false
        }
    }

    private fun checkInMediaStore(
        safeName: String,
        context: android.content.Context
    ): Boolean {
        return try {
            checkMediaStoreUri(
                context,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                safeName
            ) || checkMediaStoreUri(
                context,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                safeName
            )
        } catch (e: Exception) {
            logger.log("checkInMediaStore error: ${e.message}")
            false
        }
    }

    private fun checkMediaStoreUri(
        context: android.content.Context,
        collectionUri: Uri,
        safeName: String
    ): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("$safeName.%")

        return context.contentResolver.query(
            collectionUri, projection, selection, selectionArgs, null
        )?.use { cursor ->
            cursor.count > 0
        } ?: false
    }

    // ─── Динамические чипы качества ───────────────────────────────────────────

    private fun loadFormatsForQualityChips(info: PlaylistInfo) {
        viewModelScope.launch {
            val firstUrl = info.entries.firstOrNull()?.url
            if (firstUrl.isNullOrBlank()) {
                setDefaultQualityOptions()
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                engine.fetchVideoInfo(firstUrl)
            }

            result.fold(
                onSuccess = { videoInfo ->
                    val heights = videoInfo.formats
                        .filter { it.hasVideo && (it.height ?: 0) >= 144 }
                        .mapNotNull { it.height }
                        .distinct()
                        .sortedDescending()

                    val options = mutableListOf<PlaylistQualityMode>()
                    options.add(
                        PlaylistQualityMode(DownloadForegroundService.MODE_BEST, "Best")
                    )

                    val addedLabels = mutableSetOf<String>()
                    for (h in heights) {
                        val (mode, label) = when {
                            h >= 2160 -> DownloadForegroundService.MODE_2160 to "2160p"
                            h >= 1440 -> DownloadForegroundService.MODE_1440 to "1440p"
                            h >= 1080 -> DownloadForegroundService.MODE_1080 to "1080p"
                            h >= 720  -> "mode_720" to "720p"
                            h >= 480  -> "mode_480" to "480p"
                            h >= 360  -> "mode_360" to "360p"
                            h >= 240  -> "mode_240" to "240p"
                            else      -> "mode_144" to "144p"
                        }
                        if (addedLabels.add(label)) {
                            options.add(PlaylistQualityMode(mode, label, h))
                        }
                    }

                    if (videoInfo.formats.any { it.isAudioOnly }) {
                        options.add(
                            PlaylistQualityMode(
                                DownloadForegroundService.MODE_AUDIO, "Audio",
                                audioOnly = true
                            )
                        )
                    }

                    _qualityOptions.value = options
                    _selectedQuality.value = options.first()
                },
                onFailure = { setDefaultQualityOptions() }
            )
        }
    }

    private fun setDefaultQualityOptions() {
        val defaults = listOf(
            PlaylistQualityMode(DownloadForegroundService.MODE_BEST, "Best"),
            PlaylistQualityMode(DownloadForegroundService.MODE_1080, "1080p", 1080),
            PlaylistQualityMode(
                DownloadForegroundService.MODE_AUDIO, "Audio", audioOnly = true
            )
        )
        _qualityOptions.value = defaults
        _selectedQuality.value = defaults.first()
    }

    fun setQualityMode(mode: PlaylistQualityMode) {
        _selectedQuality.value = mode
        updateSummary(_items.value ?: emptyList())
    }

    // ─── Управление выбором элементов ─────────────────────────────────────────

    fun toggleSelected(index: Int) {
        if (_isDownloadingAll.value == true) return

        applyItemUpdate { item ->
            if (item.index != index) return@applyItemUpdate item

            if (item.downloadState == PlaylistItem.DownloadState.QUEUED ||
                item.downloadState == PlaylistItem.DownloadState.DOWNLOADING
            ) return@applyItemUpdate item

            val nowSelected = !item.isSelected

            when (item.downloadState) {
                PlaylistItem.DownloadState.DONE -> {
                    if (nowSelected) {
                        item.copy(
                            isSelected = true,
                            downloadState = PlaylistItem.DownloadState.NONE,
                            statusText = "Will re-download",
                            downloadedUri = null
                        )
                    } else {
                        item.copy(isSelected = false)
                    }
                }
                PlaylistItem.DownloadState.NONE -> item.copy(
                    isSelected = nowSelected,
                    downloadState = if (!nowSelected)
                        PlaylistItem.DownloadState.SKIPPED
                    else
                        PlaylistItem.DownloadState.NONE,
                    statusText = if (!nowSelected) "Skipped" else null
                )
                PlaylistItem.DownloadState.SKIPPED -> item.copy(
                    isSelected = nowSelected,
                    downloadState = if (nowSelected)
                        PlaylistItem.DownloadState.NONE
                    else
                        PlaylistItem.DownloadState.SKIPPED,
                    statusText = if (nowSelected) null else "Skipped"
                )
                PlaylistItem.DownloadState.FAILED -> item.copy(
                    isSelected = nowSelected,
                    statusText = if (!nowSelected) "Skipped" else "Will retry"
                )
                else -> item
            }
        }
    }

    fun selectAll() {
        if (_isDownloadingAll.value == true) return
        applyItemUpdate { item ->
            when (item.downloadState) {
                PlaylistItem.DownloadState.QUEUED,
                PlaylistItem.DownloadState.DOWNLOADING -> item
                PlaylistItem.DownloadState.DONE -> item
                PlaylistItem.DownloadState.SKIPPED -> item.copy(
                    isSelected = true,
                    downloadState = PlaylistItem.DownloadState.NONE,
                    statusText = null
                )
                else -> item.copy(isSelected = true)
            }
        }
    }

    fun skipAll() {
        if (_isDownloadingAll.value == true) return
        applyItemUpdate { item ->
            when (item.downloadState) {
                PlaylistItem.DownloadState.QUEUED,
                PlaylistItem.DownloadState.DOWNLOADING,
                PlaylistItem.DownloadState.DONE -> item
                else -> item.copy(
                    isSelected = false,
                    downloadState = PlaylistItem.DownloadState.SKIPPED,
                    statusText = "Skipped"
                )
            }
        }
    }

    fun retryFailed() {
        applyItemUpdate { item ->
            if (item.downloadState == PlaylistItem.DownloadState.FAILED) {
                item.copy(
                    isSelected = true,
                    downloadState = PlaylistItem.DownloadState.NONE,
                    statusText = null,
                    errorMessage = null
                )
            } else item
        }
    }

    fun resetCompletedForRedownload() {
        if (_isDownloadingAll.value == true) return
        applyItemUpdate { item ->
            if (item.downloadState == PlaylistItem.DownloadState.DONE) {
                item.copy(
                    isSelected = true,
                    downloadState = PlaylistItem.DownloadState.NONE,
                    statusText = null,
                    downloadedUri = null,
                    errorMessage = null
                )
            } else item
        }
        _doneCount.value = 0
    }

    // ─── Запуск скачивания ────────────────────────────────────────────────────

    fun downloadAll() {
        if (_isDownloadingAll.value == true) return

        val currentItems = _items.value ?: return
        val toDownload = currentItems.filter { item ->
            item.isSelected && item.downloadState in listOf(
                PlaylistItem.DownloadState.NONE,
                PlaylistItem.DownloadState.FAILED,
                PlaylistItem.DownloadState.SKIPPED
            )
        }

        if (toDownload.isEmpty()) {
            _uiEvent.value = "No videos selected for download"
            return
        }

        // Запоминаем размер очереди и сбрасываем счётчик выполненных
        _queueSize.value = toDownload.size
        _queueDoneCount.value = 0

        val queued = currentItems.map { item ->
            if (item in toDownload) {
                item.copy(
                    downloadState = PlaylistItem.DownloadState.QUEUED,
                    statusText = "Queued",
                    errorMessage = null
                )
            } else item
        }

        _items.value = queued
        _isDownloadingAll.value = true
        playlistStateStorage.saveItems(queued)
        updateSummary(queued)

        val context = getApplication<Application>()
        val quality = _selectedQuality.value?.serviceMode ?: DownloadForegroundService.MODE_BEST
        val json = gson.toJson(toDownload)

        val intent = Intent(context, PlaylistDownloadService::class.java).apply {
            action = PlaylistDownloadService.ACTION_START
            putExtra(PlaylistDownloadService.EXTRA_PLAYLIST_TITLE, playlistTitle())
            putExtra(PlaylistDownloadService.EXTRA_PLAYLIST_URL, playlistUrlValue)
            putExtra(PlaylistDownloadService.EXTRA_ITEMS_JSON, json)
            putExtra(PlaylistDownloadService.EXTRA_MODE, quality)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancelAll() {
        val context = getApplication<Application>()
        context.startService(
            Intent(context, PlaylistDownloadService::class.java).apply {
                action = PlaylistDownloadService.ACTION_CANCEL
            }
        )
        finishQueue(isNaturalCompletion = false)
    }

    // ─── Обработка broadcasts ─────────────────────────────────────────────────

    fun onPlaylistProgress(progress: DownloadProgress) {
        val idx = _currentDownloadIndex.value ?: return
        if (idx < 0) return

        val statusText = buildString {
            if (progress.percent > 0f) append("%.0f%%".format(progress.percent))
            if (progress.speed.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(progress.speed)
            }
            if (progress.eta.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append("ETA: ").append(progress.eta)
            }
        }.ifBlank { progress.status }

        val current = _items.value?.toMutableList() ?: return
        val listIdx = current.indexOfFirst { it.index == idx }
        if (listIdx == -1) return

        val item = current[listIdx]
        if (item.downloadState == PlaylistItem.DownloadState.DOWNLOADING) {
            current[listIdx] = item.copy(statusText = statusText)
            _items.value = current
        }
    }

    fun onPlaylistItemState(
        state: String,
        index: Int,
        title: String?,
        uri: String?,
        message: String?
    ) {
        when (state) {
            PlaylistDownloadService.STATE_ITEM_STARTED -> {
                _currentDownloadIndex.value = index
                updateItemByIndex(
                    index = index,
                    downloadState = PlaylistItem.DownloadState.DOWNLOADING,
                    statusText = message ?: "Downloading...",
                    persist = false
                )
            }

            PlaylistDownloadService.STATE_ITEM_COMPLETE -> {
                updateItemByIndex(
                    index = index,
                    downloadState = PlaylistItem.DownloadState.DONE,
                    statusText = "Downloaded",
                    downloadedUri = uri,
                    persist = true
                )
                // Увеличиваем оба счётчика: общий и счётчик текущей очереди
                _doneCount.value = (_doneCount.value ?: 0) + 1
                _queueDoneCount.value = (_queueDoneCount.value ?: 0) + 1
                _currentDownloadIndex.value = -1
            }

            PlaylistDownloadService.STATE_ITEM_ERROR -> {
                updateItemByIndex(
                    index = index,
                    downloadState = PlaylistItem.DownloadState.FAILED,
                    statusText = message ?: "Failed",
                    errorMessage = message,
                    persist = true
                )
                _currentDownloadIndex.value = -1
            }

            PlaylistDownloadService.STATE_ITEM_CANCELLED -> {
                updateItemByIndex(
                    index = index,
                    downloadState = PlaylistItem.DownloadState.NONE,
                    statusText = null,
                    persist = true
                )
                _currentDownloadIndex.value = -1
            }
        }
    }

    fun onPlaylistQueueState(
        state: String,
        completed: Int,
        total: Int,
        message: String?
    ) {
        when (state) {
            PlaylistDownloadService.STATE_QUEUE_STARTED -> {
                if (_isDownloadingAll.value != true) {
                    _isDownloadingAll.value = true
                }
            }

            PlaylistDownloadService.STATE_QUEUE_COMPLETE -> {
                // Читаем счётчики ДО вызова finishQueue — там они обнуляются
                val done = _queueDoneCount.value ?: 0
                val size = _queueSize.value.takeIf { it != null && it > 0 }
                    ?: done.coerceAtLeast(completed)

                finishQueue(isNaturalCompletion = true)

                // Формируем сообщение из данных которые прочитали до сброса
                _uiEvent.value = "Downloaded $done/$size videos"
            }

            PlaylistDownloadService.STATE_QUEUE_CANCELLED -> {
                finishQueue(isNaturalCompletion = false)
            }

            PlaylistDownloadService.STATE_QUEUE_ERROR -> {
                finishQueue(isNaturalCompletion = false)
                _uiEvent.value = message ?: "Playlist download failed"
            }
        }
    }

    // ─── Финализация очереди ──────────────────────────────────────────────────

    private fun finishQueue(isNaturalCompletion: Boolean) {
        val current = _items.value ?: emptyList()

        val updated = current.map { item ->
            when (item.downloadState) {
                PlaylistItem.DownloadState.QUEUED,
                PlaylistItem.DownloadState.DOWNLOADING -> item.copy(
                    downloadState = PlaylistItem.DownloadState.NONE,
                    statusText = null,
                    isSelected = false
                )
                PlaylistItem.DownloadState.DONE -> {
                    if (isNaturalCompletion) item.copy(isSelected = false) else item
                }
                else -> item
            }
        }

        _items.value = updated
        _doneCount.value = updated.count { it.downloadState == PlaylistItem.DownloadState.DONE }
        playlistStateStorage.saveItems(updated)
        updateSummary(updated)

        _currentDownloadIndex.value = -1
        _queueSize.value = 0
        _queueDoneCount.value = 0
        _isDownloadingAll.value = false
    }

    // ─── Вспомогательные методы ───────────────────────────────────────────────

    private fun updateItemByIndex(
        index: Int,
        downloadState: PlaylistItem.DownloadState,
        statusText: String? = null,
        downloadedUri: String? = null,
        errorMessage: String? = null,
        persist: Boolean = true
    ) {
        val current = _items.value?.toMutableList() ?: return
        val listIdx = current.indexOfFirst { it.index == index }
        if (listIdx == -1) {
            logger.log("updateItemByIndex: item index=$index not found")
            return
        }

        current[listIdx] = current[listIdx].copy(
            downloadState = downloadState,
            statusText = statusText,
            downloadedUri = downloadedUri ?: current[listIdx].downloadedUri,
            errorMessage = errorMessage
        )

        _items.value = current
        if (persist) {
            playlistStateStorage.saveItems(current)
            updateSummary(current)
        }
    }

    private fun applyItemUpdate(transform: (PlaylistItem) -> PlaylistItem) {
        val updated = (_items.value ?: emptyList()).map(transform)
        _items.value = updated
        playlistStateStorage.saveItems(updated)
        updateSummary(updated)
    }

    private fun updateSummary(items: List<PlaylistItem>) {
        val total = items.size
        val done = items.count { it.downloadState == PlaylistItem.DownloadState.DONE }
        val selected = items.count { it.isSelected }
        val failed = items.count { it.downloadState == PlaylistItem.DownloadState.FAILED }
        val downloading =
            items.count { it.downloadState == PlaylistItem.DownloadState.DOWNLOADING }
        val queued = items.count { it.downloadState == PlaylistItem.DownloadState.QUEUED }

        _summaryText.value = buildString {
            append("$total videos")
            append(" • $selected selected")
            append(" • $done downloaded")
            if (queued > 0) append(" • $queued queued")
            if (downloading > 0) append(" • $downloading downloading")
            if (failed > 0) append(" • $failed failed")
        }
    }

    private fun clearAllState() {
        _items.value = emptyList()
        _doneCount.value = 0
        _queueSize.value = 0
        _queueDoneCount.value = 0
        _qualityOptions.value = emptyList()
        _selectedQuality.value = null
        _summaryText.value = ""
        _isDownloadingAll.value = false
        _currentDownloadIndex.value = -1
        _uiEvent.value = null
        playlistStateStorage.clear()
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(200)

    // ─── Публичные геттеры ────────────────────────────────────────────────────

    fun playlistTitle(): String =
        (_state.value as? State.Loaded)?.info?.title ?: ""

    fun playlistUploader(): String? =
        (_state.value as? State.Loaded)?.info?.uploader

    fun playlistUrl(): String = playlistUrlValue

    fun getPlaylistInfoText(): String {
        val items = _items.value ?: emptyList()
        return buildString {
            append("Quality: ").append(_selectedQuality.value?.label ?: "Best").append('\n')
            append("Total: ").append(items.size).append('\n')
            append("Selected: ").append(items.count { it.isSelected }).append('\n')
            append("Done: ")
                .append(items.count { it.downloadState == PlaylistItem.DownloadState.DONE })
                .append('\n')
            append("Downloading: ")
                .append(items.count { it.downloadState == PlaylistItem.DownloadState.DOWNLOADING })
                .append('\n')
            append("Queued: ")
                .append(items.count { it.downloadState == PlaylistItem.DownloadState.QUEUED })
                .append('\n')
            append("Failed: ")
                .append(items.count { it.downloadState == PlaylistItem.DownloadState.FAILED })
                .append('\n')
            append("Skipped: ")
                .append(items.count { it.downloadState == PlaylistItem.DownloadState.SKIPPED })
                .append('\n')
        }
    }

    fun clearUiEvent() {
        _uiEvent.value = null
    }
}