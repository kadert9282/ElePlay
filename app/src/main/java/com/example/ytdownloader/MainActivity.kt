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
import com.example.ytdownloader.service.DownloadForegroundService
import com.example.ytdownloader.theme.ThemePrefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var formatAdapter: FormatAdapter
    private lateinit var downloadsAdapter: DownloadsAdapter

    private var isVideoSectionExpanded = true

    private val runtimeMessages = listOf(
        "Waking up tiny Python workers...",
        "Negotiating with native libraries...",
        "Bribing FFmpeg with stack traces...",
        "Checking if the runtime survived extraction...",
        "Teaching Android to behave like Linux...",
        "Summoning downloader spirits..."
    )

    private val downloadingMessages = listOf(
        "Negotiating with the video platform...",
        "Preparing Python runtime...",
        "Collecting video fragments...",
        "Teaching packets to behave...",
        "Convincing the network to cooperate...",
        "Doing perfectly legal downloader magic..."
    )

    private val mergingMessages = listOf(
        "Asking FFmpeg to glue things together...",
        "Merging streams like a tiny studio...",
        "Synchronizing video and audio...",
        "Trying not to anger FFmpeg...",
        "Finalizing the cinematic experience..."
    )

    private val convertingMessages = listOf(
        "Converting media atoms...",
        "Repacking bits into something pretty...",
        "Polishing the output file..."
    )

    private var runtimeMessageIndex = 0

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

        val filter = IntentFilter().apply {
            addAction(DownloadForegroundService.ACTION_PROGRESS_BROADCAST)
            addAction(DownloadForegroundService.ACTION_STATE_BROADCAST)
        }

        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            filter,
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
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveLastUrl(binding.etUrl.text?.toString().orEmpty())
        viewModel.setVideoSectionExpanded(isVideoSectionExpanded)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleShareIntent(it) }
    }

    private fun restoreUiState() {
        val savedUrl = viewModel.savedUrl.value.orEmpty()
        if (savedUrl.isNotBlank() && binding.etUrl.text?.toString() != savedUrl) {
            binding.etUrl.setText(savedUrl)
            binding.etUrl.setSelection(savedUrl.length)
        }

        isVideoSectionExpanded = viewModel.videoSectionExpanded.value ?: true
        updateVideoSectionVisibility()
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
                }
            }
        }
    }

    private fun setupUI() {
        formatAdapter = FormatAdapter { format ->
            viewModel.selectFormat(format)
        }

        downloadsAdapter = DownloadsAdapter { item ->
            openDownloadedItem(item)
        }

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

        binding.btnToggleVideoSection.setOnClickListener {
            toggleVideoSection()
        }

        binding.btnFetchInfo.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.saveLastUrl(url)
            viewModel.fetchVideoInfo(url)
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

        binding.btnCancel.setOnClickListener {
            viewModel.cancelDownload()
        }

        binding.btnSendLog.setOnClickListener { shareLogFile() }
        binding.btnShowLog.setOnClickListener { showLogDialog() }

        binding.btnChooseFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.btnToggleDownloads.setOnClickListener {
            binding.layoutDownloads.visibility =
                if (binding.layoutDownloads.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.etUrl.addTextChangedListener { text ->
            binding.tilUrl.error = null
            viewModel.saveLastUrl(text?.toString().orEmpty())
        }
    }

    private fun toggleVideoSection() {
        isVideoSectionExpanded = !isVideoSectionExpanded
        viewModel.setVideoSectionExpanded(isVideoSectionExpanded)
        updateVideoSectionVisibility()
    }

    private fun updateVideoSectionVisibility() {
        val visible = isVideoSectionExpanded

        binding.layoutVideoInfo.visibility = if (visible) View.VISIBLE else View.GONE
        binding.layoutQuickButtons.visibility =
            if (visible && viewModel.videoInfo.value != null) View.VISIBLE else View.GONE
        binding.tvMaxQuality.visibility =
            if (visible && viewModel.videoInfo.value != null) View.VISIBLE else View.GONE
        binding.tvSelectedFormat.visibility =
            if (visible && viewModel.selectedFormat.value != null) View.VISIBLE else View.GONE
        binding.tvFormatsLabel.visibility =
            if (visible && !viewModel.formats.value.isNullOrEmpty()) View.VISIBLE else View.GONE
        binding.rvFormats.visibility =
            if (visible && !viewModel.formats.value.isNullOrEmpty()) View.VISIBLE else View.GONE

        binding.btnToggleVideoSection.visibility =
            if (viewModel.videoInfo.value != null) View.VISIBLE else View.GONE

        binding.btnToggleVideoSection.text =
            if (visible) "Hide video info" else "Show video info"

        binding.btnToggleVideoSection.setIconResource(
            if (visible) android.R.drawable.arrow_up_float
            else android.R.drawable.arrow_down_float
        )
    }

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

    private fun nextRuntimeFunnyMessage(): String {
        val message = runtimeMessages[runtimeMessageIndex % runtimeMessages.size]
        runtimeMessageIndex++
        return message
    }

    private fun getFunnyStatus(progress: DownloadProgress): String {
        return when (progress.phase) {
            DownloadProgress.Phase.DOWNLOADING -> {
                val index = ((progress.percent / 20f).toInt()).coerceIn(0, downloadingMessages.lastIndex)
                downloadingMessages[index]
            }

            DownloadProgress.Phase.MERGING -> mergingMessages.random()
            DownloadProgress.Phase.CONVERTING -> convertingMessages.random()
            DownloadProgress.Phase.COMPLETE -> "Mission accomplished. File secured."
            DownloadProgress.Phase.ERROR -> "Well... that escalated quickly."
            DownloadProgress.Phase.CANCELLED -> "Download aborted by commander."
        }
    }

    private fun showSettingsDialog() {
        val themeItems = arrayOf("System theme", "Light theme", "Dark theme")
        val checked = when (viewModel.themeMode.value) {
            ThemePrefs.MODE_LIGHT -> 1
            ThemePrefs.MODE_DARK -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setSingleChoiceItems(themeItems, checked) { dialog, which ->
                when (which) {
                    0 -> {
                        viewModel.setThemeMode(ThemePrefs.MODE_SYSTEM)
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
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
            .setNeutralButton("Open GitHub") { _, _ ->
                openGithubProject()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openGithubProject() {
        val url = viewModel.githubUrl
        if (url == "Ссылка") {
            MaterialAlertDialogBuilder(this)
                .setTitle("GitHub")
                .setMessage("GitHub project link:\nСсылка")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Failed to open GitHub: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
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
            Snackbar.make(binding.root, "Failed to open file: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

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
                isVideoSectionExpanded = true
                viewModel.setVideoSectionExpanded(true)

                binding.tvTitle.text = info.title
                binding.tvDuration.text = "Duration: ${info.durationFormatted}"
                binding.tvUploader.text = info.uploader ?: ""

                info.thumbnail?.let { url ->
                    binding.ivThumbnail.load(url) { crossfade(true) }
                    binding.ivThumbnail.visibility = View.VISIBLE
                } ?: run {
                    binding.ivThumbnail.visibility = View.GONE
                }

                updateVideoSectionVisibility()
            } else {
                binding.btnToggleVideoSection.visibility = View.GONE
                binding.layoutVideoInfo.visibility = View.GONE
                binding.layoutQuickButtons.visibility = View.GONE
                binding.tvMaxQuality.visibility = View.GONE
                binding.tvSelectedFormat.visibility = View.GONE
                binding.tvFormatsLabel.visibility = View.GONE
                binding.rvFormats.visibility = View.GONE
            }
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
            binding.btnDownload.isEnabled = format != null && viewModel.isDownloading.value != true
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

        viewModel.isLoading.observe(this) { loading ->
            binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
            binding.btnFetchInfo.isEnabled = !loading
        }

        viewModel.isDownloading.observe(this) { downloading ->
            binding.layoutDownloadProgress.visibility = if (downloading) View.VISIBLE else View.GONE
            binding.btnCancel.visibility = if (downloading) View.VISIBLE else View.GONE
            binding.btnDownload.isEnabled = !downloading && viewModel.selectedFormat.value != null
            setQuickButtonsEnabled(!downloading)

            if (downloading) {
                binding.tvFunnyStatus.visibility = View.VISIBLE
                startDownloadAnimations()
            } else {
                binding.tvFunnyStatus.visibility = View.GONE
                stopDownloadAnimations()
            }
        }

        viewModel.progress.observe(this) { progress ->
            if (progress != null) {
                binding.progressBar.progress = progress.percent.toInt()
                binding.tvProgressPercent.text = "%.1f%%".format(progress.percent)
                binding.tvProgressStatus.text = progress.status
                binding.tvProgressSpeed.text =
                    if (progress.speed.isNotEmpty()) "Speed: ${progress.speed}" else ""
                binding.tvProgressEta.text =
                    if (progress.eta.isNotEmpty()) "ETA: ${progress.eta}" else ""

                binding.tvProgressPhase.text = when (progress.phase) {
                    DownloadProgress.Phase.DOWNLOADING -> "Downloading..."
                    DownloadProgress.Phase.MERGING -> "Merging video + audio..."
                    DownloadProgress.Phase.CONVERTING -> "Converting..."
                    DownloadProgress.Phase.COMPLETE -> "Complete!"
                    DownloadProgress.Phase.ERROR -> "Error"
                    DownloadProgress.Phase.CANCELLED -> "Cancelled"
                }

                binding.tvFunnyStatus.text = getFunnyStatus(progress)
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
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnFetchInfo.isEnabled = enabled
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

    private fun shareLogFile() {
        try {
            val logFile = viewModel.getLogFile()
            if (!logFile.exists()) {
                Toast.makeText(this, "No log file found", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                logFile
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
            .setMessage(if (logContent.isNotEmpty()) logContent.takeLast(5000) else "No logs yet")
            .setPositiveButton("OK", null)
            .setNeutralButton("Share") { _, _ -> shareLogFile() }
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}