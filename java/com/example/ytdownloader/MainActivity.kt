package com.example.ytdownloader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.ytdownloader.adapter.DownloadsAdapter
import com.example.ytdownloader.adapter.FormatAdapter
import com.example.ytdownloader.databinding.ActivityMainBinding
import com.example.ytdownloader.model.DownloadProgress
import com.example.ytdownloader.model.DownloadedItem
import com.example.ytdownloader.playlist.PlaylistActivity
import com.example.ytdownloader.service.DownloadForegroundService
import com.example.ytdownloader.service.PlaylistDownloadService
import com.example.ytdownloader.theme.ThemePrefs
import com.example.ytdownloader.updater.UpdateState
import com.example.ytdownloader.updater.YtDlpRelease
import com.example.ytdownloader.updater.YtDlpUpdatePrefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var formatAdapter: FormatAdapter
    private lateinit var downloadsAdapter: DownloadsAdapter

    private var isVideoSectionExpanded = true
    private var isPlaylistSectionExpanded = true

    private var updateProgressSnackbar: Snackbar? = null

    // ─── Activity Result Launchers ─────────────────────────────────────────────

    private val playlistLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            val title = data.getStringExtra(PlaylistActivity.RESULT_PLAYLIST_TITLE)
            val uploader = data.getStringExtra(PlaylistActivity.RESULT_PLAYLIST_UPLOADER)
            val count = data.getIntExtra(PlaylistActivity.RESULT_PLAYLIST_COUNT, 0)
            val url = data.getStringExtra(PlaylistActivity.RESULT_PLAYLIST_URL)
            val summary = data.getStringExtra(PlaylistActivity.RESULT_PLAYLIST_SUMMARY)

            viewModel.savePlaylistPreview(
                title = title,
                uploader = uploader,
                count = count,
                url = url,
                summary = summary
            )

            if (!url.isNullOrBlank()) {
                binding.etUrl.setText(url)
                binding.etUrl.setSelection(url.length)
                viewModel.saveLastUrl(url)
            }

            viewModel.refreshDownloads()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setSaveFolderUri(uri)
                Snackbar.make(binding.root, "Folder selected", Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(
                    binding.root,
                    "Failed to persist folder access: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    // ─── Broadcast Receivers ───────────────────────────────────────────────────

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadForegroundService.ACTION_PROGRESS_BROADCAST -> {
                    val phaseName = intent.getStringExtra("phase")
                        ?: DownloadProgress.Phase.DOWNLOADING.name
                    val phase = runCatching {
                        DownloadProgress.Phase.valueOf(phaseName)
                    }.getOrDefault(DownloadProgress.Phase.DOWNLOADING)

                    val progress = DownloadProgress(
                        percent = intent.getFloatExtra("percent", 0f),
                        downloadedBytes = intent.getLongExtra("downloadedBytes", 0L),
                        totalBytes = intent.getLongExtra("totalBytes", 0L),
                        speed = intent.getStringExtra("speed").orEmpty(),
                        eta = intent.getStringExtra("eta").orEmpty(),
                        status = intent.getStringExtra("status").orEmpty(),
                        phase = phase
                    )

                    val stillDownloading = phase != DownloadProgress.Phase.COMPLETE &&
                            phase != DownloadProgress.Phase.ERROR &&
                            phase != DownloadProgress.Phase.CANCELLED

                    viewModel.updateDownloadState(
                        isDownloading = stillDownloading,
                        progress = progress
                    )
                }

                DownloadForegroundService.ACTION_STATE_BROADCAST -> {
                    val state = intent.getStringExtra("state").orEmpty()
                    val message = intent.getStringExtra("message").orEmpty()

                    when (state) {
                        DownloadForegroundService.STATE_RUNNING -> {
                            viewModel.updateDownloadState(
                                isDownloading = true,
                                progress = viewModel.progress.value
                                    ?: DownloadProgress(status = message)
                            )
                        }

                        DownloadForegroundService.STATE_COMPLETE -> {
                            viewModel.updateDownloadState(
                                isDownloading = false,
                                progress = DownloadProgress(
                                    percent = 100f,
                                    status = "Complete!",
                                    phase = DownloadProgress.Phase.COMPLETE
                                )
                            )
                            viewModel.setSuccessMessage("Download completed")
                            viewModel.refreshDownloads()
                        }

                        DownloadForegroundService.STATE_ERROR -> {
                            viewModel.updateDownloadState(
                                isDownloading = false,
                                progress = DownloadProgress(
                                    status = message,
                                    phase = DownloadProgress.Phase.ERROR
                                )
                            )
                            viewModel.setErrorMessage(message)
                        }

                        DownloadForegroundService.STATE_CANCELLED -> {
                            viewModel.updateDownloadState(
                                isDownloading = false,
                                progress = DownloadProgress(
                                    status = "Cancelled",
                                    phase = DownloadProgress.Phase.CANCELLED
                                )
                            )
                            viewModel.setSuccessMessage("Download cancelled")
                        }
                    }
                }
            }
        }
    }

    private val playlistItemCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlaylistDownloadService.ACTION_ITEM_COMPLETED_FOR_MAIN) {
                viewModel.refreshDownloads()
            }
        }
    }

    // ─── Funny messages ────────────────────────────────────────────────────────

    private val runtimeMessages = listOf(
        "Waking up tiny Python workers...",
        "Negotiating with native libraries...",
        "Bribing FFmpeg with stack traces...",
        "Checking if the runtime survived extraction...",
        "Teaching Android to behave like Linux...",
        "Summoning downloader spirits..."
    )

    private val mergingMessages = listOf(
        "Merging your video... Almost there!",
        "Gluing video and audio together...",
        "Asking FFmpeg to work its magic...",
        "Synchronizing streams, please wait...",
        "FFmpeg is doing its thing...",
        "Finalizing your cinematic masterpiece..."
    )

    private val convertingMessages = listOf(
        "Converting media format...",
        "Repacking bits into something pretty...",
        "Polishing the output file...",
        "Almost done, hang tight..."
    )

    private var runtimeMessageIndex = 0
    private var mergingMessageIndex = 0
    private var convertingMessageIndex = 0

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupObservers()
        restoreUiState()
        requestNotificationPermission()
        handleShareIntent(intent)
        startRuntimeAnimations()
        viewModel.initializeRuntime()
        viewModel.refreshDownloads()
    }

    override fun onStart() {
        super.onStart()

        val singleDownloadFilter = IntentFilter().apply {
            addAction(DownloadForegroundService.ACTION_PROGRESS_BROADCAST)
            addAction(DownloadForegroundService.ACTION_STATE_BROADCAST)
        }
        ContextCompat.registerReceiver(
            this, downloadReceiver, singleDownloadFilter, RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            playlistItemCompletedReceiver,
            IntentFilter(PlaylistDownloadService.ACTION_ITEM_COMPLETED_FOR_MAIN),
            RECEIVER_NOT_EXPORTED
        )

        if (DownloadForegroundService.isRunning) {
            viewModel.updateDownloadState(
                isDownloading = true,
                progress = DownloadForegroundService.currentProgress
                    ?: DownloadProgress(status = "Downloading...")
            )
        } else {
            val progress = DownloadForegroundService.currentProgress
            if (progress != null) {
                val downloading =
                    DownloadForegroundService.currentState == DownloadForegroundService.STATE_RUNNING
                viewModel.updateDownloadState(downloading, progress)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(downloadReceiver)
        try {
            unregisterReceiver(playlistItemCompletedReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveLastUrl(binding.etUrl.text?.toString().orEmpty())
        viewModel.setVideoSectionExpanded(isVideoSectionExpanded)
        viewModel.setPlaylistSectionExpanded(isPlaylistSectionExpanded)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleShareIntent(it) }
    }

    // ─── UI Setup ──────────────────────────────────────────────────────────────

    private fun restoreUiState() {
        val savedUrl = viewModel.savedUrl.value.orEmpty()
        if (savedUrl.isNotBlank() && binding.etUrl.text?.toString() != savedUrl) {
            binding.etUrl.setText(savedUrl)
            binding.etUrl.setSelection(savedUrl.length)
        }

        isVideoSectionExpanded = viewModel.videoSectionExpanded.value ?: true
        isPlaylistSectionExpanded = viewModel.playlistSectionExpanded.value ?: true

        updateVideoSectionVisibility()
        updatePlaylistSectionVisibility()
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                val urlRegex = Regex("(https?://[^\\s]+)")
                val match = urlRegex.find(sharedText)
                if (match != null) {
                    binding.etUrl.setText(match.value)
                    viewModel.saveLastUrl(match.value)
                    if (looksLikePlaylistUrl(match.value)) {
                        viewModel.forceUpdatePlaylistPreview(match.value)
                    }
                }
            }
        }
    }

    private fun setupUI() {
        formatAdapter = FormatAdapter { format -> viewModel.selectFormat(format) }
        downloadsAdapter = DownloadsAdapter { item -> openDownloadedItem(item) }

        binding.rvFormats.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = formatAdapter
        }
        binding.rvDownloads.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = downloadsAdapter
        }

        binding.btnPaste.setOnClickListener { pasteFromClipboard() }

        binding.btnClearUrl.setOnClickListener {
            binding.etUrl.setText("")
            viewModel.saveLastUrl("")
        }

        binding.btnToggleVideoSection.setOnClickListener { toggleVideoSection() }
        binding.btnTogglePlaylistPreviewSection.setOnClickListener { togglePlaylistSection() }

        binding.btnFetchInfo.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.saveLastUrl(url)
            viewModel.fetchVideoInfo(url)
        }

        binding.btnFetchPlaylist.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (!viewModel.isValidWebUrl(url)) {
                binding.tilUrl.error = "Enter a valid playlist URL"
                return@setOnClickListener
            }
            if (!looksLikePlaylistUrl(url)) {
                binding.tilUrl.error = "This is not a playlist URL"
                return@setOnClickListener
            }
            binding.tilUrl.error = null
            viewModel.saveLastUrl(url)
            viewModel.forceUpdatePlaylistPreview(url)
            openPlaylist(url)
        }

        binding.btnOpenPlaylistScreen.setOnClickListener {
            val intent = viewModel.openPlaylistIntent()
            if (intent != null) playlistLauncher.launch(intent)
        }

        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.saveLastUrl(url)
            viewModel.downloadSelected(url)
        }

        binding.btnBest.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.saveLastUrl(url)
            viewModel.downloadBest(url)
        }

        binding.btnMax.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.saveLastUrl(url)
            viewModel.downloadBest(url)
        }

        binding.btn1080p.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.saveLastUrl(url)
            viewModel.download1080p(url)
        }

        binding.btn1440p.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.saveLastUrl(url)
            viewModel.download1440p(url)
        }

        binding.btn2160p.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.saveLastUrl(url)
            viewModel.download2160p(url)
        }

        binding.btnAudio.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.saveLastUrl(url)
            viewModel.downloadAudio(url)
        }

        binding.btnCancel.setOnClickListener { viewModel.cancelDownload() }
        binding.btnSendLog.setOnClickListener { shareLogFile() }
        binding.btnShowLog.setOnClickListener { showLogDialog() }
        binding.btnChooseFolder.setOnClickListener { folderPickerLauncher.launch(null) }

        binding.btnToggleDownloads.setOnClickListener {
            binding.layoutDownloads.visibility =
                if (binding.layoutDownloads.visibility == View.VISIBLE) View.GONE
                else View.VISIBLE
        }

        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        binding.etUrl.addTextChangedListener { text ->
            binding.tilUrl.error = null
            viewModel.saveLastUrl(text?.toString().orEmpty())
        }
    }

    // ─── Section visibility ────────────────────────────────────────────────────

    private fun toggleVideoSection() {
        isVideoSectionExpanded = !isVideoSectionExpanded
        viewModel.setVideoSectionExpanded(isVideoSectionExpanded)
        updateVideoSectionVisibility()
    }

    private fun togglePlaylistSection() {
        isPlaylistSectionExpanded = !isPlaylistSectionExpanded
        viewModel.setPlaylistSectionExpanded(isPlaylistSectionExpanded)
        updatePlaylistSectionVisibility()
    }

    private fun updateVideoSectionVisibility() {
        val hasVideo = viewModel.videoInfo.value != null
        val visible = hasVideo && isVideoSectionExpanded

        binding.btnToggleVideoSection.visibility = if (hasVideo) View.VISIBLE else View.GONE
        binding.layoutVideoInfo.visibility = if (visible) View.VISIBLE else View.GONE
        binding.layoutQuickButtons.visibility =
            if (visible && !viewModel.formats.value.isNullOrEmpty()) View.VISIBLE else View.GONE
        binding.tvMaxQuality.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvSelectedFormat.visibility =
            if (visible && viewModel.selectedFormat.value != null) View.VISIBLE else View.GONE
        binding.tvFormatsLabel.visibility =
            if (visible && !viewModel.formats.value.isNullOrEmpty()) View.VISIBLE else View.GONE
        binding.rvFormats.visibility =
            if (visible && !viewModel.formats.value.isNullOrEmpty()) View.VISIBLE else View.GONE

        binding.btnToggleVideoSection.text =
            if (visible) "Hide video info" else "Show video info"
        binding.btnToggleVideoSection.setIconResource(
            if (visible) android.R.drawable.arrow_up_float
            else android.R.drawable.arrow_down_float
        )
    }

    private fun updatePlaylistSectionVisibility() {
        val hasPlaylist = !viewModel.playlistTitle.value.isNullOrBlank()
        val visible = hasPlaylist && isPlaylistSectionExpanded

        binding.btnTogglePlaylistPreviewSection.visibility =
            if (hasPlaylist) View.VISIBLE else View.GONE
        binding.layoutPlaylistPreview.visibility = if (visible) View.VISIBLE else View.GONE

        binding.btnTogglePlaylistPreviewSection.text =
            if (visible) "Hide playlist info" else "Show playlist info"
        binding.btnTogglePlaylistPreviewSection.setIconResource(
            if (visible) android.R.drawable.arrow_up_float
            else android.R.drawable.arrow_down_float
        )
    }

    // ─── Animations ────────────────────────────────────────────────────────────

    private fun startRuntimeAnimations() {
        binding.ivRuntimeGear.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.rotate_slow)
        )
        binding.viewRuntimeWave.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.pulse_soft)
        )
    }

    private fun stopRuntimeAnimations() {
        binding.ivRuntimeGear.clearAnimation()
        binding.viewRuntimeWave.clearAnimation()
    }

    private fun startDownloadAnimations() {
        binding.ivLoadingGear.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.rotate_slow)
        )
        binding.viewLoadingWave.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.pulse_soft)
        )
    }

    private fun stopDownloadAnimations() {
        binding.ivLoadingGear.clearAnimation()
        binding.viewLoadingWave.clearAnimation()
    }

    // ─── Observers ─────────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.runtimeStatus.observe(this) { status ->
            when (status) {
                is MainViewModel.RuntimeStatus.NotInitialized -> {
                    binding.tvRuntimeStatus.text = "Runtime not initialized"
                    binding.tvRuntimeFunny.text = nextRuntimeFunnyMessage()
                    binding.cardRuntimeLoading.visibility = View.VISIBLE
                    binding.layoutMainContent.visibility = View.GONE
                }
                is MainViewModel.RuntimeStatus.Initializing -> {
                    binding.tvRuntimeStatus.text = "Initializing: ${status.message}"
                    binding.tvRuntimeFunny.text = nextRuntimeFunnyMessage()
                    binding.cardRuntimeLoading.visibility = View.VISIBLE
                    binding.layoutMainContent.visibility = View.GONE
                    binding.progressInit.visibility = View.VISIBLE
                }
                is MainViewModel.RuntimeStatus.Ready -> {
                    binding.tvRuntimeStatus.text = "Runtime ready ✓"
                    binding.tvRuntimeFunny.text = "All systems go."
                    binding.progressInit.visibility = View.GONE
                    stopRuntimeAnimations()
                    binding.cardRuntimeLoading.visibility = View.GONE
                    binding.layoutMainContent.visibility = View.VISIBLE
                    setControlsEnabled(true)
                    updateVideoSectionVisibility()
                    updatePlaylistSectionVisibility()
                }
                is MainViewModel.RuntimeStatus.Error -> {
                    binding.tvRuntimeStatus.text = "Runtime error: ${status.message}"
                    binding.tvRuntimeFunny.text = "The runtime had other plans."
                    binding.progressInit.visibility = View.GONE
                    stopRuntimeAnimations()
                    binding.cardRuntimeLoading.visibility = View.VISIBLE
                    binding.layoutMainContent.visibility = View.GONE
                    setControlsEnabled(false)
                }
            }
        }

        viewModel.runtimeLog.observe(this) { log ->
            binding.tvRuntimeLog.text = log
        }

        viewModel.saveFolderUri.observe(this) { uri ->
            binding.tvChosenFolder.text = uri?.toString() ?: "Default folders: Movies / Music"
        }

        viewModel.maxQualityLabel.observe(this) { label ->
            binding.tvMaxQuality.text = label
        }

        viewModel.maxButtonLabel.observe(this) { label ->
            binding.btnMax.text = label
        }

        viewModel.is1080Available.observe(this) { available ->
            binding.btn1080p.visibility = if (available) View.VISIBLE else View.GONE
        }

        viewModel.is1440Available.observe(this) { available ->
            binding.btn1440p.visibility = if (available) View.VISIBLE else View.GONE
        }

        viewModel.is2160Available.observe(this) { available ->
            binding.btn2160p.visibility = if (available) View.VISIBLE else View.GONE
        }

        viewModel.videoInfo.observe(this) { info ->
            if (info != null) {
                binding.tvTitle.text = info.title
                binding.tvDuration.text = "Duration: ${info.durationFormatted}"
                binding.tvUploader.text = info.uploader ?: ""

                info.thumbnail?.let { url ->
                    binding.ivThumbnail.load(url) { crossfade(true) }
                    binding.ivThumbnail.visibility = View.VISIBLE
                } ?: run {
                    binding.ivThumbnail.visibility = View.GONE
                }

                isVideoSectionExpanded = true
            }
            updateVideoSectionVisibility()
        }

        viewModel.playlistTitle.observe(this) { title ->
            binding.tvPlaylistPreviewTitle.text = title ?: ""
            updatePlaylistSectionVisibility()
        }

        viewModel.playlistUploader.observe(this) { uploader ->
            val count = viewModel.playlistCount.value ?: 0
            binding.tvPlaylistPreviewMeta.text = buildString {
                if (!uploader.isNullOrBlank()) append(uploader)
                if (count > 0) {
                    if (isNotEmpty()) append(" • ")
                    append(count).append(" videos")
                }
            }
        }

        viewModel.playlistCount.observe(this) { count ->
            val uploader = viewModel.playlistUploader.value
            binding.tvPlaylistPreviewMeta.text = buildString {
                if (!uploader.isNullOrBlank()) append(uploader)
                if (count > 0) {
                    if (isNotEmpty()) append(" • ")
                    append(count).append(" videos")
                }
            }
        }

        viewModel.playlistSummary.observe(this) { summary ->
            binding.tvPlaylistPreviewSummary.text = summary ?: ""
        }

        viewModel.formats.observe(this) { formats ->
            formatAdapter.submitList(formats)
            if (viewModel.videoInfo.value != null) {
                updateVideoSectionVisibility()
            } else {
                binding.rvFormats.visibility = View.GONE
                binding.tvFormatsLabel.visibility = View.GONE
            }
        }

        viewModel.downloads.observe(this) { items ->
            downloadsAdapter.submitList(items)
            binding.tvDownloadsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.selectedFormat.observe(this) { format ->
            binding.btnDownload.isEnabled =
                format != null && viewModel.isDownloading.value != true
            binding.tvSelectedFormat.text = format?.displayName ?: "No format selected"
            binding.tvSelectedFormat.visibility =
                if (isVideoSectionExpanded && format != null) View.VISIBLE else View.GONE
            formatAdapter.setSelected(format)
        }

        viewModel.savedUrl.observe(this) { savedUrl ->
            val current = binding.etUrl.text?.toString().orEmpty()
            if (savedUrl != current && savedUrl.isNotBlank()) {
                binding.etUrl.setText(savedUrl)
                binding.etUrl.setSelection(savedUrl.length)
            }
        }

        viewModel.videoSectionExpanded.observe(this) { expanded ->
            isVideoSectionExpanded = expanded
            updateVideoSectionVisibility()
        }

        viewModel.playlistSectionExpanded.observe(this) { expanded ->
            isPlaylistSectionExpanded = expanded
            updatePlaylistSectionVisibility()
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
            binding.btnFetchInfo.isEnabled = !loading
        }

        viewModel.isDownloading.observe(this) { downloading ->
            binding.layoutDownloadProgress.visibility =
                if (downloading) View.VISIBLE else View.GONE
            binding.btnCancel.visibility = if (downloading) View.VISIBLE else View.GONE
            binding.btnDownload.isEnabled =
                !downloading && viewModel.selectedFormat.value != null
            setQuickButtonsEnabled(!downloading)

            if (downloading) {
                startDownloadAnimations()
            } else {
                stopDownloadAnimations()
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutMergingProgress.visibility = View.GONE
                binding.tvFunnyStatus.visibility = View.GONE
            }
        }

        viewModel.progress.observe(this) { progress ->
            if (progress == null) {
                binding.tvProgressPercent.text = ""
                binding.tvProgressStatus.text = ""
                binding.tvProgressSpeed.text = ""
                binding.tvProgressEta.text = ""
                binding.progressBar.progress = 0
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutMergingProgress.visibility = View.GONE
                return@observe
            }

            when (progress.phase) {
                DownloadProgress.Phase.DOWNLOADING -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.layoutMergingProgress.visibility = View.GONE
                    val pct = progress.percent.toInt().coerceIn(0, 100)
                    binding.progressBar.progress = pct
                    binding.tvProgressPercent.text = "%.1f%%".format(progress.percent)
                    binding.tvProgressPhase.text = "Downloading..."
                    binding.tvProgressStatus.text = progress.status
                    binding.tvProgressSpeed.text =
                        if (progress.speed.isNotBlank()) "⬇ ${progress.speed}" else ""
                    binding.tvProgressEta.text =
                        if (progress.eta.isNotBlank()) "ETA: ${progress.eta}" else ""
                    binding.tvFunnyStatus.visibility = View.VISIBLE
                    binding.tvFunnyStatus.text = getFunnyDownloadingMessage(progress.percent)
                }

                DownloadProgress.Phase.MERGING -> {
                    binding.progressBar.visibility = View.GONE
                    binding.layoutMergingProgress.visibility = View.VISIBLE
                    binding.tvProgressPercent.text = ""
                    binding.tvProgressPhase.text = "Merging..."
                    binding.tvMergingLabel.text = getNextMergingMessage()
                    binding.tvProgressStatus.text =
                        progress.status.ifBlank { "Please wait..." }
                    binding.tvProgressSpeed.text = ""
                    binding.tvProgressEta.text = ""
                    binding.tvFunnyStatus.visibility = View.VISIBLE
                    binding.tvFunnyStatus.text = "FFmpeg is doing its thing..."
                }

                DownloadProgress.Phase.CONVERTING -> {
                    binding.progressBar.visibility = View.GONE
                    binding.layoutMergingProgress.visibility = View.VISIBLE
                    binding.tvProgressPercent.text = ""
                    binding.tvProgressPhase.text = "Converting..."
                    binding.tvMergingLabel.text = getNextConvertingMessage()
                    binding.tvProgressStatus.text =
                        progress.status.ifBlank { "Please wait..." }
                    binding.tvProgressSpeed.text = ""
                    binding.tvProgressEta.text = ""
                    binding.tvFunnyStatus.visibility = View.VISIBLE
                    binding.tvFunnyStatus.text = "Repacking bits into something pretty..."
                }

                DownloadProgress.Phase.COMPLETE -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.layoutMergingProgress.visibility = View.GONE
                    binding.progressBar.progress = 100
                    binding.tvProgressPercent.text = "100%"
                    binding.tvProgressPhase.text = "Complete! ✓"
                    binding.tvProgressStatus.text = progress.status
                    binding.tvProgressSpeed.text = ""
                    binding.tvProgressEta.text = ""
                    binding.tvFunnyStatus.visibility = View.VISIBLE
                    binding.tvFunnyStatus.text = "Mission accomplished. File secured."
                }

                DownloadProgress.Phase.ERROR -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.layoutMergingProgress.visibility = View.GONE
                    binding.tvProgressPhase.text = "Error"
                    binding.tvProgressStatus.text = progress.status
                    binding.tvProgressPercent.text = ""
                    binding.tvProgressSpeed.text = ""
                    binding.tvProgressEta.text = ""
                    binding.tvFunnyStatus.visibility = View.VISIBLE
                    binding.tvFunnyStatus.text = "Well... that escalated quickly."
                }

                DownloadProgress.Phase.CANCELLED -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.layoutMergingProgress.visibility = View.GONE
                    binding.tvProgressPhase.text = "Cancelled"
                    binding.tvProgressStatus.text = "Download cancelled"
                    binding.tvProgressPercent.text = ""
                    binding.tvProgressSpeed.text = ""
                    binding.tvProgressEta.text = ""
                    binding.tvFunnyStatus.visibility = View.GONE
                }
            }
        }

        viewModel.errorMessage.observe(this) { error ->
            if (error != null) {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG)
                    .setAction("Dismiss") { viewModel.clearError() }
                    .show()
            }
        }

        viewModel.successMessage.observe(this) { message ->
            if (message != null) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                    .setAction("OK") { viewModel.clearSuccess() }
                    .show()
            }
        }

        viewModel.updateState.observe(this) { state ->
            handleUpdateState(state)
        }
    }

    // ─── Update state handler ──────────────────────────────────────────────────

    private fun handleUpdateState(state: UpdateState) {
        when (state) {
            is UpdateState.Idle -> {
                updateProgressSnackbar?.dismiss()
                updateProgressSnackbar = null
            }

            is UpdateState.Checking -> {
                updateProgressSnackbar?.dismiss()
                updateProgressSnackbar = Snackbar.make(
                    binding.root,
                    "Loading yt-dlp versions...",
                    Snackbar.LENGTH_INDEFINITE
                ).also { it.show() }
            }

            is UpdateState.ReleasesLoaded -> {
                updateProgressSnackbar?.dismiss()
                updateProgressSnackbar = null
                viewModel.resetUpdateState()
                showReleasesDialog(state.releases)
            }

            is UpdateState.UpdateAvailable -> {
                updateProgressSnackbar?.dismiss()
                updateProgressSnackbar = null
                viewModel.resetUpdateState()
            }

            is UpdateState.UpToDate -> {
                updateProgressSnackbar?.dismiss()
                updateProgressSnackbar = null
                viewModel.resetUpdateState()
            }

            is UpdateState.Downloading -> {
                val msg = "Downloading yt-dlp: ${state.percent}%"
                if (updateProgressSnackbar == null) {
                    updateProgressSnackbar = Snackbar.make(
                        binding.root,
                        msg,
                        Snackbar.LENGTH_INDEFINITE
                    ).also { it.show() }
                } else {
                    updateProgressSnackbar?.setText(msg)
                }
            }

            is UpdateState.Installing -> {
                updateProgressSnackbar?.setText("Installing yt-dlp...")
            }

            is UpdateState.Success -> {
                updateProgressSnackbar?.dismiss()
                updateProgressSnackbar = null

                Snackbar.make(
                    binding.root,
                    "yt-dlp installed successfully! ✓",
                    Snackbar.LENGTH_LONG
                )
                    .setAction("Use it now") {
                        viewModel.setYtDlpMode(YtDlpUpdatePrefs.MODE_UPDATED)
                    }
                    .show()

                viewModel.resetUpdateState()
            }

            is UpdateState.Error -> {
                updateProgressSnackbar?.dismiss()
                updateProgressSnackbar = null

                Snackbar.make(
                    binding.root,
                    "Error: ${state.message}",
                    Snackbar.LENGTH_LONG
                )
                    .setAction("Retry") {
                        viewModel.fetchReleases()
                    }
                    .show()

                viewModel.resetUpdateState()
            }
        }
    }

    // ─── Controls ──────────────────────────────────────────────────────────────

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnFetchInfo.isEnabled = enabled
        binding.btnFetchPlaylist.isEnabled = enabled
        binding.btnTogglePlaylistPreviewSection.isEnabled = enabled
        binding.btnOpenPlaylistScreen.isEnabled = enabled
        binding.etUrl.isEnabled = enabled
        binding.btnPaste.isEnabled = enabled
        binding.btnChooseFolder.isEnabled = enabled
        binding.btnToggleDownloads.isEnabled = enabled
        binding.btnSettings.isEnabled = enabled
        setQuickButtonsEnabled(enabled)
    }

    private fun setQuickButtonsEnabled(enabled: Boolean) {
        binding.btnBest.isEnabled = enabled
        binding.btnMax.isEnabled = enabled
        binding.btnAudio.isEnabled = enabled
        binding.btn1080p.isEnabled = enabled
        binding.btn1440p.isEnabled = enabled
        binding.btn2160p.isEnabled = enabled
    }

    // ─── Navigation ────────────────────────────────────────────────────────────

    private fun looksLikePlaylistUrl(url: String): Boolean =
        url.contains("playlist", true) || url.contains("list=", true)

    private fun openPlaylist(url: String) {
        val intent = Intent(this, PlaylistActivity::class.java).apply {
            putExtra(PlaylistActivity.EXTRA_URL, url)
        }
        playlistLauncher.launch(intent)
    }

    private fun openDownloadedItem(item: DownloadedItem) {
        try {
            val uri = Uri.parse(item.uriString)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Snackbar.make(
                binding.root,
                "Failed to open file: ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    // ─── Clipboard ─────────────────────────────────────────────────────────────

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            val urlRegex = Regex("(https?://[^\\s]+)")
            val match = urlRegex.find(text)
            if (match != null) {
                binding.etUrl.setText(match.value)
                binding.etUrl.setSelection(match.value.length)
                viewModel.saveLastUrl(match.value)
                if (looksLikePlaylistUrl(match.value)) {
                    viewModel.forceUpdatePlaylistPreview(match.value)
                }
                Toast.makeText(this, "URL pasted", Toast.LENGTH_SHORT).show()
            } else {
                binding.etUrl.setText(text)
                binding.etUrl.setSelection(text.length)
                viewModel.saveLastUrl(text)
                Toast.makeText(this, "Text pasted", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Funny messages ────────────────────────────────────────────────────────

    private fun nextRuntimeFunnyMessage(): String {
        val msg = runtimeMessages[runtimeMessageIndex % runtimeMessages.size]
        runtimeMessageIndex++
        return msg
    }

    private fun getNextMergingMessage(): String {
        val msg = mergingMessages[mergingMessageIndex % mergingMessages.size]
        mergingMessageIndex++
        return msg
    }

    private fun getNextConvertingMessage(): String {
        val msg = convertingMessages[convertingMessageIndex % convertingMessages.size]
        convertingMessageIndex++
        return msg
    }

    private fun getFunnyDownloadingMessage(percent: Float): String = when {
        percent < 10f -> "Negotiating with the video platform..."
        percent < 25f -> "Collecting video fragments..."
        percent < 50f -> "Teaching packets to behave..."
        percent < 75f -> "Convincing the network to cooperate..."
        percent < 90f -> "Doing perfectly legal downloader magic..."
        else -> "Almost there, finishing up..."
    }

    // ─── Settings ──────────────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val builtinVersion = viewModel.getBuiltinVersion()
        val updatedVersion = viewModel.getUpdatedVersion()
        val isUpdateInstalled = viewModel.isUpdateInstalled()
        val isUpdatedMode = viewModel.getYtDlpMode() == YtDlpUpdatePrefs.MODE_UPDATED

        val activeVersion = if (isUpdatedMode && isUpdateInstalled)
            "Updated ($updatedVersion)" else "Built-in ($builtinVersion)"

        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setMessage(
                "Built-in: $builtinVersion\n" +
                        "Updated: ${
                            if (isUpdateInstalled && updatedVersion != null)
                                updatedVersion else "not installed"
                        }\n" +
                        "Active: $activeVersion"
            )
            .setPositiveButton("yt-dlp") { _, _ -> showYtDlpOptionsDialog() }
            .setNeutralButton("Themes") { _, _ -> showThemeDialog() }
            .setNegativeButton("GitHub") { _, _ -> openGithubProject() }
            .show()
    }

    // ─── Theme dialog ──────────────────────────────────────────────────────────

    private fun showThemeDialog() {
        val themeItems = arrayOf("System theme", "Light theme", "Dark theme")
        val checkedTheme = when (viewModel.themeMode.value) {
            ThemePrefs.MODE_LIGHT -> 1
            ThemePrefs.MODE_DARK -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Themes")
            .setSingleChoiceItems(themeItems, checkedTheme) { dialog, which ->
                when (which) {
                    0 -> {
                        viewModel.setThemeMode(ThemePrefs.MODE_SYSTEM)
                        AppCompatDelegate.setDefaultNightMode(
                            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        )
                    }
                    1 -> {
                        viewModel.setThemeMode(ThemePrefs.MODE_LIGHT)
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    }
                    2 -> {
                        viewModel.setThemeMode(ThemePrefs.MODE_DARK)
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ─── yt-dlp options dialog ─────────────────────────────────────────────────

    private fun showYtDlpOptionsDialog() {
        val isUpdateInstalled = viewModel.isUpdateInstalled()
        val currentMode = viewModel.getYtDlpMode()
        val isUpdatedMode = currentMode == YtDlpUpdatePrefs.MODE_UPDATED
        val builtinVersion = viewModel.getBuiltinVersion()
        val updatedVersion = viewModel.getUpdatedVersion()

        val modeItems = arrayOf(
            "Built-in yt-dlp ($builtinVersion)",
            "Updated yt-dlp (${updatedVersion ?: "not installed"})"
        )
        val checkedMode = if (isUpdatedMode && isUpdateInstalled) 1 else 0

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("yt-dlp options")
            .setSingleChoiceItems(modeItems, checkedMode) { _, which ->
                when (which) {
                    0 -> viewModel.setYtDlpMode(YtDlpUpdatePrefs.MODE_BUILTIN)
                    1 -> {
                        if (!isUpdateInstalled) {
                            Snackbar.make(
                                binding.root,
                                "Please download a version first",
                                Snackbar.LENGTH_SHORT
                            ).show()
                        } else {
                            viewModel.setYtDlpMode(YtDlpUpdatePrefs.MODE_UPDATED)
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .setPositiveButton("Download yt-dlp") { _, _ ->
                viewModel.fetchReleases()
            }

        if (isUpdateInstalled) {
            builder.setNeutralButton("Remove") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Remove update?")
                    .setMessage(
                        "Updated yt-dlp ($updatedVersion) will be deleted.\n" +
                                "Built-in version ($builtinVersion) will be used."
                    )
                    .setPositiveButton("Remove") { _, _ ->
                        viewModel.removeUpdate()
                        Snackbar.make(
                            binding.root,
                            "Update removed. Using built-in yt-dlp.",
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        builder.show()
    }

    // ─── Releases dialog ───────────────────────────────────────────────────────

    private fun showReleasesDialog(releases: List<YtDlpRelease>) {
        val installedVersion = viewModel.getUpdatedVersion()

        val items = releases.map { release ->
            buildString {
                append(release.version)
                if (release.isLatest) append("  ★ latest")
                if (release.version == installedVersion) append("  ✓ installed")
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Select yt-dlp version")
            .setItems(items) { _, which ->
                val selected = releases[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle("Download yt-dlp ${selected.version}?")
                    .setMessage(
                        buildString {
                            if (selected.isLatest) appendLine("This is the latest version.")
                            if (selected.version == installedVersion) {
                                appendLine("This version is already installed.")
                            }
                            appendLine("\nThe file will be downloaded and installed.")
                        }
                    )
                    .setPositiveButton("Download") { _, _ ->
                        viewModel.downloadAndInstallUpdate(
                            downloadUrl = selected.downloadUrl,
                            version = selected.version
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ─── GitHub ────────────────────────────────────────────────────────────────

    private fun openGithubProject() {
        val url = viewModel.githubUrl
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Snackbar.make(
                binding.root,
                "Failed to open GitHub: ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    // ─── Logs ──────────────────────────────────────────────────────────────────

    private fun shareLogFile() {
        try {
            val logFile = viewModel.getLogFile()
            if (!logFile.exists()) {
                Toast.makeText(this, "No log file found", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", logFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share log file"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share log: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLogDialog() {
        val logContent = viewModel.getLogContent()
        MaterialAlertDialogBuilder(this)
            .setTitle("Log")
            .setMessage(
                if (logContent.isNotEmpty()) logContent.takeLast(5000)
                else "No logs yet"
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Share") { _, _ -> shareLogFile() }
            .show()
    }

    // ─── Permissions ───────────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}