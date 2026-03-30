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
import com.example.ytdownloader.service.DownloadForegroundService
import com.example.ytdownloader.storage.DownloadsRepository
import com.example.ytdownloader.storage.StoragePrefs
import com.example.ytdownloader.storage.UiStateStorage
import com.example.ytdownloader.theme.ThemePrefs
import com.example.ytdownloader.util.FileLogger
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

    val githubUrl: String = "https://github.com/ren10-14/ElePlay/tree/main"

    sealed class RuntimeStatus {
        data object NotInitialized : RuntimeStatus()
        data class Initializing(val message: String) : RuntimeStatus()
        data object Ready : RuntimeStatus()
        data class Error(val message: String) : RuntimeStatus()
    }

    init {
        restoreUiState()
    }

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

    fun setThemeMode(mode: String) {
        themePrefs.setThemeMode(mode)
        _themeMode.value = mode
    }

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

    fun setVideoSectionExpanded(expanded: Boolean) {
        uiStateStorage.saveVideoSectionExpanded(expanded)
        _videoSectionExpanded.value = expanded
    }

    private fun appendRuntimeLog(message: String) {
        val current = _runtimeLog.value ?: ""
        _runtimeLog.value = if (current.isEmpty()) message else "$current\n$message"
    }

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

                    logger.log("Loaded ${filteredFormats.size} formats")
                    logger.log("Max quality: ${_maxQualityLabel.value}")
                    logger.log("1080 available: ${_is1080Available.value}")
                    logger.log("1440 available: ${_is1440Available.value}")
                    logger.log("2160 available: ${_is2160Available.value}")
                },
                onFailure = { error ->
                    _errorMessage.value = error.message ?: "Failed to fetch video info"
                    logger.log("Fetch error: ${error.message}")
                }
            )

            _isLoading.value = false
        }
    }

    fun selectFormat(format: FormatItem) {
        _selectedFormat.value = format
        uiStateStorage.saveSelectedFormat(format)
    }

    fun downloadSelected(url: String) {
        val normalizedUrl = url.trim()
        val format = _selectedFormat.value

        if (normalizedUrl.isBlank()) {
            _errorMessage.value = "Please enter a URL"
            return
        }

        if (!isValidWebUrl(normalizedUrl)) {
            _errorMessage.value = "Invalid URL"
            return
        }

        if (format == null) {
            _errorMessage.value = "Please select a format"
            return
        }

        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"
            return
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

        if (normalizedUrl.isBlank()) {
            _errorMessage.value = "Please enter a URL"
            return
        }

        if (!isValidWebUrl(normalizedUrl)) {
            _errorMessage.value = "Invalid URL"
            return
        }

        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"
            return
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

        if (normalizedUrl.isBlank()) {
            _errorMessage.value = "Please enter a URL"
            return
        }

        if (!isValidWebUrl(normalizedUrl)) {
            _errorMessage.value = "Invalid URL"
            return
        }

        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"
            return
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

        if (normalizedUrl.isBlank()) {
            _errorMessage.value = "Please enter a URL"
            return
        }

        if (!isValidWebUrl(normalizedUrl)) {
            _errorMessage.value = "Invalid URL"
            return
        }

        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"
            return
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

        if (normalizedUrl.isBlank()) {
            _errorMessage.value = "Please enter a URL"
            return
        }

        if (!isValidWebUrl(normalizedUrl)) {
            _errorMessage.value = "Invalid URL"
            return
        }

        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"
            return
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

        if (normalizedUrl.isBlank()) {
            _errorMessage.value = "Please enter a URL"
            return
        }

        if (!isValidWebUrl(normalizedUrl)) {
            _errorMessage.value = "Invalid URL"
            return
        }

        val info = _videoInfo.value ?: run {
            _errorMessage.value = "Load video info first"
            return
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

    private fun isValidWebUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            !uri.host.isNullOrBlank() && (scheme == "http" || scheme == "https")
        } catch (e: Exception) {
            false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearSuccess() {
        _successMessage.value = null
    }

    fun getLogFile(): File = logger.getLogFile()

    fun getLogContent(): String = logger.getLogContent()

    override fun onCleared() {
        super.onCleared()
    }
}