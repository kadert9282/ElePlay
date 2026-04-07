package com.example.ytdownloader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.ytdownloader.engine.PythonYtDlpEngine
import com.example.ytdownloader.engine.VideoEngine
import com.example.ytdownloader.model.DownloadProgress
import com.example.ytdownloader.model.DownloadedItem
import com.example.ytdownloader.model.FormatItem
import com.example.ytdownloader.model.VideoInfo
import com.example.ytdownloader.playlist.PlaylistActivity
import com.example.ytdownloader.service.DownloadForegroundService
import com.example.ytdownloader.storage.DownloadsRepository
import com.example.ytdownloader.storage.StoragePrefs
import com.example.ytdownloader.storage.UiStateStorage
import com.example.ytdownloader.theme.ThemePrefs
import com.example.ytdownloader.updater.UpdateState
import com.example.ytdownloader.updater.YtDlpUpdatePrefs
import com.example.ytdownloader.updater.YtDlpUpdater
import com.example.ytdownloader.util.FileLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val logger = FileLogger(application)
    private val engine: VideoEngine = PythonYtDlpEngine(application, logger)
    private val storagePrefs = StoragePrefs(application)
    private val downloadsRepository = DownloadsRepository(application)
    private val themePrefs = ThemePrefs(application)
    private val uiStateStorage = UiStateStorage(application)

    // ─── Updater ──────────────────────────────────────────────────────────────
    private val updatePrefs = YtDlpUpdatePrefs(application)
    private val updater = YtDlpUpdater(
        context = application,
        paths = (engine as PythonYtDlpEngine).getRuntimePaths(),
        prefs = updatePrefs,
        logger = logger
    )

    // ─── LiveData ─────────────────────────────────────────────────────────────

    private val _runtimeStatus = MutableLiveData<RuntimeStatus>(RuntimeStatus.NotInitialized)
    val runtimeStatus: LiveData<RuntimeStatus> = _runtimeStatus

    private val _runtimeLog = MutableLiveData("")
    val runtimeLog: LiveData<String> = _runtimeLog

    private val _videoInfo = MutableLiveData<VideoInfo?>()
    val videoInfo: LiveData<VideoInfo?> = _videoInfo

    private val _formats = MutableLiveData<List<FormatItem>>(emptyList())
    val formats: LiveData<List<FormatItem>> = _formats

    private val _selectedFormat = MutableLiveData<FormatItem?>()
    val selectedFormat: LiveData<FormatItem?> = _selectedFormat

    private val _progress = MutableLiveData<DownloadProgress?>()
    val progress: LiveData<DownloadProgress?> = _progress

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isDownloading = MutableLiveData(false)
    val isDownloading: LiveData<Boolean> = _isDownloading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _successMessage = MutableLiveData<String?>()
    val successMessage: LiveData<String?> = _successMessage

    private val _saveFolderUri = MutableLiveData<Uri?>(storagePrefs.getSaveTreeUri())
    val saveFolderUri: LiveData<Uri?> = _saveFolderUri

    private val _downloads = MutableLiveData<List<DownloadedItem>>(downloadsRepository.getAll())
    val downloads: LiveData<List<DownloadedItem>> = _downloads

    private val _maxQualityLabel = MutableLiveData("Max available: unknown")
    val maxQualityLabel: LiveData<String> = _maxQualityLabel

    private val _maxButtonLabel = MutableLiveData("Max")
    val maxButtonLabel: LiveData<String> = _maxButtonLabel

    private val _themeMode = MutableLiveData(themePrefs.getThemeMode())
    val themeMode: LiveData<String> = _themeMode

    private val _is1080Available = MutableLiveData(false)
    val is1080Available: LiveData<Boolean> = _is1080Available

    private val _is1440Available = MutableLiveData(false)
    val is1440Available: LiveData<Boolean> = _is1440Available

    private val _is2160Available = MutableLiveData(false)
    val is2160Available: LiveData<Boolean> = _is2160Available

    private val _savedUrl = MutableLiveData(uiStateStorage.getLastUrl())
    val savedUrl: LiveData<String> = _savedUrl

    private val _videoSectionExpanded = MutableLiveData(uiStateStorage.isVideoSectionExpanded())
    val videoSectionExpanded: LiveData<Boolean> = _videoSectionExpanded

    private val _playlistSectionExpanded =
        MutableLiveData(uiStateStorage.isPlaylistSectionExpanded())
    val playlistSectionExpanded: LiveData<Boolean> = _playlistSectionExpanded

    private val _playlistTitle = MutableLiveData(uiStateStorage.getPlaylistTitle())
    val playlistTitle: LiveData<String?> = _playlistTitle

    private val _playlistUploader = MutableLiveData(uiStateStorage.getPlaylistUploader())
    val playlistUploader: LiveData<String?> = _playlistUploader

    private val _playlistCount = MutableLiveData(uiStateStorage.getPlaylistCount())
    val playlistCount: LiveData<Int> = _playlistCount

    private val _playlistUrl = MutableLiveData(uiStateStorage.getPlaylistUrl())
    val playlistUrl: LiveData<String?> = _playlistUrl

    private val _playlistSummary = MutableLiveData(uiStateStorage.getPlaylistSummary())
    val playlistSummary: LiveData<String?> = _playlistSummary

    // ─── Update LiveData ──────────────────────────────────────────────────────

    private val _updateState = MutableLiveData<UpdateState>(UpdateState.Idle)
    val updateState: LiveData<UpdateState> = _updateState

    private val _ytDlpMode = MutableLiveData(updatePrefs.getMode())
    val ytDlpMode: LiveData<String> = _ytDlpMode

    // ─── Internal ─────────────────────────────────────────────────────────────

    private var playlistPreviewJob: Job? = null
    private var lastPlaylistPreviewRequestedUrl: String? = null
    private var isPlaylistPreviewLoading = false

    val githubUrl: String = "https://github.com/ren10-14/ElePlay/tree/main"

    sealed class RuntimeStatus {
        data object NotInitialized : RuntimeStatus()
        data class Initializing(val message: String) : RuntimeStatus()
        data object Ready : RuntimeStatus()
        data class Error(val message: String) : RuntimeStatus()
    }

    init {
        restoreUiState()
        // Применяем сохранённый режим yt-dlp к engine при старте
        applyYtDlpModeToEngine()
    }

    // ─── Runtime ──────────────────────────────────────────────────────────────

    fun initializeRuntime() {
        viewModelScope.launch {
            _runtimeStatus.value = RuntimeStatus.Initializing("Starting...")
            _runtimeLog.value = ""

            if (engine.isRuntimeReady()) {
                _runtimeStatus.value = RuntimeStatus.Ready
                _runtimeLog.value = "Runtime already initialized"
                logger.log("Runtime already ready")
                return@launch
            }

            engine.initializeRuntime()
                .catch { e ->
                    _runtimeStatus.value = RuntimeStatus.Error(e.message ?: "Unknown error")
                    appendRuntimeLog("ERROR: ${e.message}")
                    logger.log("Runtime init failed: ${e.message}")
                }
                .collect { message ->
                    _runtimeStatus.value = RuntimeStatus.Initializing(message)
                    appendRuntimeLog(message)

                    if (message.contains("complete", ignoreCase = true)) {
                        _runtimeStatus.value = RuntimeStatus.Ready
                    }
                    if (message.startsWith("ERROR:")) {
                        _runtimeStatus.value = RuntimeStatus.Error(message)
                    }
                }
        }
    }

    // ─── Restore ──────────────────────────────────────────────────────────────

    private fun restoreUiState() {
        val info = uiStateStorage.getVideoInfo()
        val formats = uiStateStorage.getFormats()
        val selected = uiStateStorage.getSelectedFormat()

        _videoInfo.value = info
        _formats.value = formats
        _selectedFormat.value = selected

        if (formats.isNotEmpty()) {
            _maxQualityLabel.value = buildMaxQualityLabel(formats)
            _maxButtonLabel.value = buildMaxButtonLabel(formats)
            _is1080Available.value = isQualityAvailable(formats, 1080)
            _is1440Available.value = isQualityAvailable(formats, 1440)
            _is2160Available.value = isQualityAvailable(formats, 2160)
        }
    }

    // ─── Theme ────────────────────────────────────────────────────────────────

    fun setThemeMode(mode: String) {
        themePrefs.setThemeMode(mode)
        _themeMode.value = mode
    }

    // ─── Storage ──────────────────────────────────────────────────────────────

    fun setSaveFolderUri(uri: Uri?) {
        storagePrefs.setSaveTreeUri(uri)
        _saveFolderUri.value = uri
    }

    fun refreshDownloads() {
        _downloads.value = downloadsRepository.getAll()
    }

    fun saveLastUrl(url: String) {
        uiStateStorage.saveLastUrl(url)
        _savedUrl.value = url
    }

    // ─── Section expanded ─────────────────────────────────────────────────────

    fun setVideoSectionExpanded(expanded: Boolean) {
        uiStateStorage.saveVideoSectionExpanded(expanded)
        _videoSectionExpanded.value = expanded
    }

    fun setPlaylistSectionExpanded(expanded: Boolean) {
        uiStateStorage.savePlaylistSectionExpanded(expanded)
        _playlistSectionExpanded.value = expanded
    }

    private fun expandVideoSection() {
        uiStateStorage.saveVideoSectionExpanded(true)
        _videoSectionExpanded.value = true
    }

    // ─── Playlist preview ─────────────────────────────────────────────────────

    fun savePlaylistPreview(
        title: String?,
        uploader: String?,
        count: Int,
        url: String?,
        summary: String?
    ) {
        uiStateStorage.savePlaylistPreview(title, uploader, count, url, summary)
        _playlistTitle.value = title
        _playlistUploader.value = uploader
        _playlistCount.value = count
        _playlistUrl.value = url
        _playlistSummary.value = summary
        if (!url.isNullOrBlank()) lastPlaylistPreviewRequestedUrl = url
    }

    fun clearPlaylistPreview() {
        playlistPreviewJob?.cancel()
        isPlaylistPreviewLoading = false
        uiStateStorage.clearPlaylistPreview()
        _playlistTitle.value = null
        _playlistUploader.value = null
        _playlistCount.value = 0
        _playlistUrl.value = null
        _playlistSummary.value = null
        lastPlaylistPreviewRequestedUrl = null
    }

    // ─── Fetch video info ─────────────────────────────────────────────────────

    fun fetchVideoInfo(url: String) {
        val normalizedUrl = url.trim()

        if (normalizedUrl.isBlank()) {
            _errorMessage.value = "Please enter a URL"
            return
        }
        if (!isValidWebUrl(normalizedUrl)) {
            _errorMessage.value = "Invalid URL"
            return
        }

        saveLastUrl(normalizedUrl)

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _videoInfo.value = null
            _formats.value = emptyList()
            _selectedFormat.value = null
            _maxQualityLabel.value = "Max available: unknown"
            _maxButtonLabel.value = "Max"
            _is1080Available.value = false
            _is1440Available.value = false
            _is2160Available.value = false
            uiStateStorage.clearVideoState()

            val result = engine.fetchVideoInfo(normalizedUrl)

            result.fold(
                onSuccess = { info ->
                    _videoInfo.value = info

                    val filteredFormats = info.formats
                        .filter { format ->
                            when {
                                format.isMuxed -> (format.height ?: 0) >= 360
                                format.isVideoOnly -> (format.height ?: 0) >= 144
                                format.isAudioOnly -> (format.abr ?: 0f) >= 48f
                                else -> false
                            }
                        }
                        .sortedWith(
                            compareByDescending<FormatItem> { it.height ?: 0 }
                                .thenByDescending { it.fps ?: 0 }
                                .thenByDescending { it.tbr ?: 0f }
                                .thenBy {
                                    when {
                                        it.isMuxed -> 0
                                        it.isVideoOnly -> 1
                                        it.isAudioOnly -> 2
                                        else -> 3
                                    }
                                }
                        )

                    _formats.value = filteredFormats
                    _maxQualityLabel.value = buildMaxQualityLabel(filteredFormats)
                    _maxButtonLabel.value = buildMaxButtonLabel(filteredFormats)
                    _is1080Available.value = isQualityAvailable(filteredFormats, 1080)
                    _is1440Available.value = isQualityAvailable(filteredFormats, 1440)
                    _is2160Available.value = isQualityAvailable(filteredFormats, 2160)

                    uiStateStorage.saveVideoInfo(info)
                    uiStateStorage.saveFormats(filteredFormats)
                    uiStateStorage.saveSelectedFormat(null)
                    expandVideoSection()

                    logger.log("Loaded ${filteredFormats.size} formats")
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "Failed to fetch video info"
                    logger.log("Fetch error: ${error.message}")
                }
            )

            _isLoading.value = false
        }
    }

    // ─── Playlist preview fetch ───────────────────────────────────────────────

    fun updatePlaylistPreviewFromUrl(url: String) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return
        if (!(normalizedUrl.contains("playlist", true) ||
                    normalizedUrl.contains("list=", true))
        ) return
        if (!isValidWebUrl(normalizedUrl)) return

        playlistPreviewJob?.cancel()

        playlistPreviewJob = viewModelScope.launch {
            delay(350)
            isPlaylistPreviewLoading = true
            lastPlaylistPreviewRequestedUrl = normalizedUrl
            saveLastUrl(normalizedUrl)

            val result = engine.fetchPlaylistInfo(normalizedUrl)
            result.fold(
                onSuccess = { info ->
                    val summary = buildString {
                        append(info.entries.size).append(" videos")
                        if (!info.uploader.isNullOrBlank()) {
                            append(" • ").append(info.uploader)
                        }
                    }
                    savePlaylistPreview(
                        title = info.title,
                        uploader = info.uploader,
                        count = info.entries.size,
                        url = normalizedUrl,
                        summary = summary
                    )
                },
                onFailure = { clearPlaylistPreview() }
            )

            isPlaylistPreviewLoading = false
        }
    }

    fun forceUpdatePlaylistPreview(url: String) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return
        if (!isValidWebUrl(normalizedUrl)) return
        clearPlaylistPreview()
        updatePlaylistPreviewFromUrl(normalizedUrl)
    }

    fun openPlaylistIntent(): Intent? {
        val url = _playlistUrl.value ?: return null
        val context = getApplication<Application>()
        return Intent(context, PlaylistActivity::class.java).apply {
            putExtra(PlaylistActivity.EXTRA_URL, url)
        }
    }

    // ─── Format selection ─────────────────────────────────────────────────────

    fun selectFormat(format: FormatItem) {
        _selectedFormat.value = format
        uiStateStorage.saveSelectedFormat(format)
    }

    // ─── Downloads ────────────────────────────────────────────────────────────

    fun downloadSelected(url: String) {
        val normalizedUrl = url.trim()
        val format = _selectedFormat.value

        if (normalizedUrl.isBlank()) { _errorMessage.value = "Please enter a URL"; return }
        if (!isValidWebUrl(normalizedUrl)) { _errorMessage.value = "Invalid URL"; return }
        if (format == null) { _errorMessage.value = "Please select a format"; return }
        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"; return
        }

        saveLastUrl(normalizedUrl)
        startForegroundDownload(
            url = normalizedUrl,
            title = info.title,
            fileName = sanitizeFileName(info.title),
            mode = DownloadForegroundService.MODE_SELECTED,
            format = format
        )
    }

    fun downloadBest(url: String) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) { _errorMessage.value = "Please enter a URL"; return }
        if (!isValidWebUrl(normalizedUrl)) { _errorMessage.value = "Invalid URL"; return }
        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"; return
        }

        saveLastUrl(normalizedUrl)
        startForegroundDownload(
            url = normalizedUrl,
            title = info.title,
            fileName = sanitizeFileName(info.title),
            mode = DownloadForegroundService.MODE_BEST
        )
    }

    fun download1080p(url: String) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) { _errorMessage.value = "Please enter a URL"; return }
        if (!isValidWebUrl(normalizedUrl)) { _errorMessage.value = "Invalid URL"; return }
        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"; return
        }

        saveLastUrl(normalizedUrl)
        startForegroundDownload(
            url = normalizedUrl,
            title = info.title,
            fileName = sanitizeFileName(info.title),
            mode = DownloadForegroundService.MODE_1080
        )
    }

    fun download1440p(url: String) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) { _errorMessage.value = "Please enter a URL"; return }
        if (!isValidWebUrl(normalizedUrl)) { _errorMessage.value = "Invalid URL"; return }
        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"; return
        }

        saveLastUrl(normalizedUrl)
        startForegroundDownload(
            url = normalizedUrl,
            title = info.title,
            fileName = sanitizeFileName(info.title),
            mode = DownloadForegroundService.MODE_1440
        )
    }

    fun download2160p(url: String) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) { _errorMessage.value = "Please enter a URL"; return }
        if (!isValidWebUrl(normalizedUrl)) { _errorMessage.value = "Invalid URL"; return }
        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"; return
        }

        saveLastUrl(normalizedUrl)
        startForegroundDownload(
            url = normalizedUrl,
            title = info.title,
            fileName = sanitizeFileName(info.title),
            mode = DownloadForegroundService.MODE_2160
        )
    }

    fun downloadAudio(url: String) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) { _errorMessage.value = "Please enter a URL"; return }
        if (!isValidWebUrl(normalizedUrl)) { _errorMessage.value = "Invalid URL"; return }
        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"; return
        }

        saveLastUrl(normalizedUrl)
        startForegroundDownload(
            url = normalizedUrl,
            title = info.title,
            fileName = sanitizeFileName(info.title),
            mode = DownloadForegroundService.MODE_AUDIO
        )
    }

    private fun startForegroundDownload(
        url: String,
        title: String,
        fileName: String,
        mode: String,
        format: FormatItem? = null
    ) {
        val context = getApplication<Application>()
        val intent = Intent(context, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_START
            putExtra(DownloadForegroundService.EXTRA_URL, url)
            putExtra(DownloadForegroundService.EXTRA_TITLE, title)
            putExtra(DownloadForegroundService.EXTRA_FILE_NAME, fileName)
            putExtra(DownloadForegroundService.EXTRA_MODE, mode)

            format?.let {
                putExtra(DownloadForegroundService.EXTRA_FORMAT_ID, it.formatId)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_EXT, it.ext)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_RESOLUTION, it.resolution)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_FILESIZE, it.filesize ?: -1L)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_FPS, it.fps ?: -1)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_VCODEC, it.vcodec)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_ACODEC, it.acodec)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_ABR, it.abr ?: -1f)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_TBR, it.tbr ?: -1f)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_NOTE, it.formatNote)
                putExtra(DownloadForegroundService.EXTRA_HAS_VIDEO, it.hasVideo)
                putExtra(DownloadForegroundService.EXTRA_HAS_AUDIO, it.hasAudio)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_HEIGHT, it.height ?: -1)
                putExtra(DownloadForegroundService.EXTRA_FORMAT_WIDTH, it.width ?: -1)
            }
        }

        ContextCompat.startForegroundService(context, intent)
        _isDownloading.value = true
        _progress.value = DownloadProgress(status = "Starting download...")
        _errorMessage.value = null
        _successMessage.value = null
    }

    fun cancelDownload() {
        val context = getApplication<Application>()
        val intent = Intent(context, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_CANCEL
        }
        context.startService(intent)
    }

    fun updateDownloadState(isDownloading: Boolean, progress: DownloadProgress?) {
        _isDownloading.postValue(isDownloading)
        _progress.postValue(progress)
    }

    fun setSuccessMessage(message: String) {
        _successMessage.postValue(message)
    }

    fun setErrorMessage(message: String) {
        _errorMessage.postValue(message)
    }

    // ─── yt-dlp updater ───────────────────────────────────────────────────────

    fun getBuiltinVersion(): String = updater.getBuiltinVersion()

    fun getUpdatedVersion(): String? = updater.getUpdatedVersion()

    fun isUpdateInstalled(): Boolean = updater.isUpdateInstalled()

    fun getYtDlpMode(): String = updatePrefs.getMode()

    fun setYtDlpMode(mode: String) {
        updatePrefs.setMode(mode)
        _ytDlpMode.value = mode
        applyYtDlpModeToEngine()
        logger.log("yt-dlp mode set to: $mode, applying to engine...")
    }

    private fun applyYtDlpModeToEngine() {
        val modeIsUpdated = updatePrefs.isUpdatedMode()
        val updateInstalled = updater.isUpdateInstalled()
        val useUpdated = modeIsUpdated && updateInstalled
        (engine as PythonYtDlpEngine).setUseUpdatedYtDlp(useUpdated)
        logger.log(
            "applyYtDlpModeToEngine: mode=${updatePrefs.getMode()}, " +
                    "installed=$updateInstalled, useUpdated=$useUpdated"
        )
    }

    fun fetchReleases() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            val result = updater.fetchAllReleases()
            result.fold(
                onSuccess = { releases ->
                    _updateState.value = UpdateState.ReleasesLoaded(releases)
                },
                onFailure = { e ->
                    _updateState.value = UpdateState.Error(
                        e.message ?: "Failed to fetch releases"
                    )
                }
            )
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            val result = updater.checkForUpdate()
            result.fold(
                onSuccess = { check ->
                    _updateState.value = if (check.hasUpdate) {
                        UpdateState.UpdateAvailable(
                            latestVersion = check.latestVersion,
                            currentVersion = check.currentVersion,
                            downloadUrl = check.downloadUrl
                        )
                    } else {
                        UpdateState.UpToDate
                    }
                },
                onFailure = { e ->
                    _updateState.value = UpdateState.Error(
                        e.message ?: "Failed to check for updates"
                    )
                }
            )
        }
    }

    fun downloadAndInstallUpdate(downloadUrl: String, version: String) {
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(0)

            val result = updater.downloadAndInstall(
                downloadUrl = downloadUrl,
                version = version,
                onProgress = { percent ->
                    if (percent < 100) {
                        _updateState.postValue(UpdateState.Downloading(percent))
                    } else {
                        _updateState.postValue(UpdateState.Installing)
                    }
                }
            )

            result.fold(
                onSuccess = {
                    // Если пользователь уже выбрал режим updated —
                    // автоматически переключаем движок не дожидаясь "Use it now"
                    if (updatePrefs.getMode() == YtDlpUpdatePrefs.MODE_UPDATED) {
                        applyYtDlpModeToEngine()
                        logger.log("Auto-switched to updated yt-dlp after install")
                    } else {
                        logger.log("Update installed, waiting for user to switch mode")
                    }
                    _updateState.value = UpdateState.Success
                },
                onFailure = { e ->
                    _updateState.value = UpdateState.Error(
                        e.message ?: "Installation failed"
                    )
                }
            )
        }
    }

    fun removeUpdate() {
        updater.removeUpdate()
        setYtDlpMode(YtDlpUpdatePrefs.MODE_BUILTIN)
        _updateState.value = UpdateState.Idle
        logger.log("Update removed, reverted to builtin")
    }

    fun resetUpdateState() {
        _updateState.value = UpdateState.Idle
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun appendRuntimeLog(message: String) {
        val current = _runtimeLog.value ?: ""
        _runtimeLog.value = if (current.isEmpty()) message else "$current\n$message"
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

    private fun isQualityAvailable(formats: List<FormatItem>, height: Int): Boolean {
        return formats.any { it.hasVideo && (it.height ?: 0) >= height }
    }

    private fun buildMaxQualityLabel(formats: List<FormatItem>): String {
        val best = findBestVideoFormat(formats) ?: return "Max available: unknown"
        val height = best.height ?: return "Max available: ${best.resolution}"
        val fpsSuffix = best.fps?.let { if (it > 30) " ${it}fps" else "" } ?: ""
        val typeSuffix = when {
            best.isMuxed -> " muxed"
            best.isVideoOnly -> " video"
            else -> ""
        }
        return "Max available: ${height}p$fpsSuffix$typeSuffix"
    }

    private fun buildMaxButtonLabel(formats: List<FormatItem>): String {
        val best = findBestVideoFormat(formats) ?: return "Max"
        val h = best.height ?: return "Max"
        val fpsSuffix = best.fps?.let { if (it > 30) " ${it}fps" else "" } ?: ""
        return "Max ${h}p$fpsSuffix"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
    }

    fun isValidWebUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            !uri.host.isNullOrBlank() && (scheme == "http" || scheme == "https")
        } catch (e: Exception) {
            false
        }
    }

    fun clearError() { _errorMessage.value = null }
    fun clearSuccess() { _successMessage.value = null }

    fun getLogFile(): File = logger.getLogFile()
    fun getLogContent(): String = logger.getLogContent()

    override fun onCleared() {
        super.onCleared()
        playlistPreviewJob?.cancel()
    }
}