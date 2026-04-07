package com.example.ytdownloader.playlist

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ytdownloader.databinding.ActivityPlaylistBinding
import com.example.ytdownloader.model.DownloadProgress
import com.example.ytdownloader.model.PlaylistItem
import com.example.ytdownloader.service.PlaylistDownloadService
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class PlaylistActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "playlist_url"
        const val RESULT_PLAYLIST_TITLE = "result_playlist_title"
        const val RESULT_PLAYLIST_UPLOADER = "result_playlist_uploader"
        const val RESULT_PLAYLIST_COUNT = "result_playlist_count"
        const val RESULT_PLAYLIST_URL = "result_playlist_url"
        const val RESULT_PLAYLIST_SUMMARY = "result_playlist_summary"
    }

    private lateinit var binding: ActivityPlaylistBinding
    private val viewModel: PlaylistViewModel by viewModels()
    private lateinit var adapter: PlaylistAdapter

    private var currentUrl: String = ""

    // ─── BroadcastReceiver ────────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                PlaylistDownloadService.ACTION_PROGRESS_BROADCAST -> {
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

                    viewModel.onPlaylistProgress(progress)

                    binding.tvOverallSpeed.text = progress.speed
                    binding.tvOverallEta.text =
                        if (progress.eta.isNotBlank()) "ETA: ${progress.eta}" else ""
                }

                PlaylistDownloadService.ACTION_ITEM_STATE_BROADCAST -> {
                    val state = intent.getStringExtra("state").orEmpty()
                    val index =
                        intent.getIntExtra(PlaylistDownloadService.EXTRA_ITEM_INDEX, -1)
                    val title =
                        intent.getStringExtra(PlaylistDownloadService.EXTRA_ITEM_TITLE)
                    val uri =
                        intent.getStringExtra(PlaylistDownloadService.EXTRA_ITEM_URI)
                    val message =
                        intent.getStringExtra(PlaylistDownloadService.EXTRA_ITEM_MESSAGE)

                    if (index >= 0) {
                        viewModel.onPlaylistItemState(state, index, title, uri, message)
                    }
                }

                PlaylistDownloadService.ACTION_QUEUE_STATE_BROADCAST -> {
                    val state = intent.getStringExtra("state").orEmpty()
                    val completed = intent.getIntExtra("completed", 0)
                    val total = intent.getIntExtra("total", 0)
                    val message = intent.getStringExtra("message")
                    viewModel.onPlaylistQueueState(state, completed, total, message)
                }
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finishWithResult() }

        setupAdapter()
        setupClicks()
        setupObservers()

        currentUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (currentUrl.isBlank()) {
            finish()
            return
        }

        if (savedInstanceState == null || viewModel.playlistUrl() != currentUrl) {
            viewModel.fetchPlaylist(currentUrl)
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceivers()
        binding.root.postDelayed({ checkAndSyncStaleState() }, 400)
    }

    override fun onStop() {
        super.onStop()
        safeUnregisterReceiver()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithResult()
    }

    // ─── Синхронизация зависшего состояния ───────────────────────────────────

    private fun checkAndSyncStaleState() {
        val isDownloading = viewModel.isDownloadingAll.value ?: false
        if (!isDownloading) return

        val items = viewModel.items.value ?: return
        val hasActive = items.any {
            it.downloadState == PlaylistItem.DownloadState.DOWNLOADING ||
                    it.downloadState == PlaylistItem.DownloadState.QUEUED
        }

        if (!hasActive) {
            val done = viewModel.queueDoneCount.value ?: 0
            val size = viewModel.queueSize.value?.takeIf { it > 0 } ?: items.size

            viewModel.onPlaylistQueueState(
                PlaylistDownloadService.STATE_QUEUE_COMPLETE,
                done,
                size,
                null
            )
        }
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupAdapter() {
        adapter = PlaylistAdapter(
            onToggleSelected = { index -> viewModel.toggleSelected(index) }
        )
        binding.rvPlaylist.apply {
            layoutManager = LinearLayoutManager(this@PlaylistActivity)
            adapter = this@PlaylistActivity.adapter
        }
    }

    private fun setupClicks() {
        binding.btnDownloadAll.setOnClickListener { viewModel.downloadAll() }
        binding.btnCancelAll.setOnClickListener { viewModel.cancelAll() }
        binding.btnRetry.setOnClickListener {
            if (currentUrl.isNotBlank()) viewModel.fetchPlaylist(currentUrl)
        }
        binding.btnSelectAll.setOnClickListener { viewModel.selectAll() }
        binding.btnSkipAll.setOnClickListener { viewModel.skipAll() }
        binding.btnRetryFailed.setOnClickListener { viewModel.retryFailed() }
        binding.btnResetCompleted.setOnClickListener {
            viewModel.resetCompletedForRedownload()
        }
        binding.btnShowPlaylistInfo.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Playlist Info")
                .setMessage(viewModel.getPlaylistInfoText())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ─── Observers ────────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.state.observe(this) { state ->
            when (state) {
                is PlaylistViewModel.State.Fetching -> {
                    binding.layoutFetching.visibility = View.VISIBLE
                    binding.layoutHeader.visibility = View.GONE
                    binding.layoutError.visibility = View.GONE
                    binding.divider.visibility = View.GONE
                    binding.rvPlaylist.visibility = View.GONE
                }
                is PlaylistViewModel.State.Loaded -> {
                    binding.layoutFetching.visibility = View.GONE
                    binding.layoutHeader.visibility = View.VISIBLE
                    binding.layoutError.visibility = View.GONE
                    binding.divider.visibility = View.VISIBLE
                    binding.rvPlaylist.visibility = View.VISIBLE

                    binding.toolbar.title = state.info.title
                    binding.tvPlaylistTitle.text = state.info.title
                    binding.tvPlaylistUploader.text = state.info.uploader ?: ""
                    binding.tvPlaylistUploader.visibility =
                        if (state.info.uploader.isNullOrBlank()) View.GONE else View.VISIBLE
                }
                is PlaylistViewModel.State.Error -> {
                    binding.layoutFetching.visibility = View.GONE
                    binding.layoutHeader.visibility = View.GONE
                    binding.layoutError.visibility = View.VISIBLE
                    binding.divider.visibility = View.GONE
                    binding.rvPlaylist.visibility = View.GONE
                    binding.tvError.text = state.message
                }
                is PlaylistViewModel.State.Idle -> {
                    binding.layoutFetching.visibility = View.GONE
                    binding.layoutHeader.visibility = View.GONE
                    binding.layoutError.visibility = View.GONE
                    binding.divider.visibility = View.GONE
                    binding.rvPlaylist.visibility = View.GONE
                }
            }
        }

        viewModel.items.observe(this) { items ->
            adapter.submitList(items.toList())
        }

        // Общая статистика плейлиста — только текст
        viewModel.doneCount.observe(this) { done ->
            val total = viewModel.items.value?.size ?: 0
            binding.tvPlaylistCount.text = "$total videos • $done downloaded"
        }

        // Прогресс текущей очереди — "X / Y done"
        viewModel.queueDoneCount.observe(this) { done ->
            val queueSize = viewModel.queueSize.value ?: 0
            updateQueueProgressUi(done, queueSize)
        }

        viewModel.queueSize.observe(this) { queueSize ->
            val done = viewModel.queueDoneCount.value ?: 0
            updateQueueProgressUi(done, queueSize)
        }

        viewModel.summaryText.observe(this) { summary ->
            binding.tvPlaylistSummary.text = summary
        }

        viewModel.isDownloadingAll.observe(this) { downloading ->
            binding.btnDownloadAll.visibility =
                if (downloading) View.GONE else View.VISIBLE
            binding.btnCancelAll.visibility =
                if (downloading) View.VISIBLE else View.GONE

            // Показываем блок прогресса только во время скачивания
            binding.layoutOverallProgress.visibility =
                if (downloading) View.VISIBLE else View.GONE

            binding.btnSelectAll.isEnabled = !downloading
            binding.btnSkipAll.isEnabled = !downloading
            binding.btnRetryFailed.isEnabled = !downloading
            binding.btnResetCompleted.isEnabled = !downloading
            binding.chipGroupQualities.isEnabled = !downloading

            if (!downloading) {
                binding.tvOverallSpeed.text = ""
                binding.tvOverallEta.text = ""
                binding.tvOverallPercent.text = ""
            }
        }

        viewModel.currentDownloadIndex.observe(this) { idx ->
            if (idx >= 0 && idx < adapter.itemCount) {
                binding.rvPlaylist.smoothScrollToPosition(idx)
            }
        }

        viewModel.qualityOptions.observe(this) { options ->
            buildQualityChips(options, viewModel.selectedQuality.value)
        }

        viewModel.selectedQuality.observe(this) { selected ->
            binding.tvQualityMode.text = "Quality: ${selected?.label ?: "Best"}"
            syncChipSelection(selected)
        }

        viewModel.uiEvent.observe(this) { message ->
            if (message != null) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                    .setAction("OK") { viewModel.clearUiEvent() }
                    .show()
                viewModel.clearUiEvent()
            }
        }
    }

    // ─── Прогресс очереди ─────────────────────────────────────────────────────

    /**
     * Показывает "X / Y done" где Y — количество видео в очереди,
     * а не общее количество в плейлисте.
     */
    private fun updateQueueProgressUi(done: Int, queueSize: Int) {
        if (queueSize <= 0) {
            binding.tvOverallPercent.text = ""
            return
        }
        binding.tvOverallPercent.text = "$done / $queueSize done"
    }

    // ─── Quality chips ────────────────────────────────────────────────────────

    private fun buildQualityChips(
        options: List<PlaylistViewModel.PlaylistQualityMode>,
        selected: PlaylistViewModel.PlaylistQualityMode?
    ) {
        binding.chipGroupQualities.removeAllViews()
        options.forEach { option ->
            val chip = Chip(this).apply {
                text = option.label
                isCheckable = true
                isChecked = option.label == selected?.label
                tag = option
                setOnClickListener { viewModel.setQualityMode(option) }
            }
            binding.chipGroupQualities.addView(chip)
        }
    }

    private fun syncChipSelection(selected: PlaylistViewModel.PlaylistQualityMode?) {
        for (i in 0 until binding.chipGroupQualities.childCount) {
            val chip = binding.chipGroupQualities.getChildAt(i) as? Chip ?: continue
            val option = chip.tag as? PlaylistViewModel.PlaylistQualityMode ?: continue
            chip.setOnClickListener(null)
            chip.isChecked = option.label == selected?.label
            chip.setOnClickListener { viewModel.setQualityMode(option) }
        }
    }

    // ─── Receivers ────────────────────────────────────────────────────────────

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(PlaylistDownloadService.ACTION_PROGRESS_BROADCAST)
            addAction(PlaylistDownloadService.ACTION_ITEM_STATE_BROADCAST)
            addAction(PlaylistDownloadService.ACTION_QUEUE_STATE_BROADCAST)
        }
        ContextCompat.registerReceiver(
            this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun safeUnregisterReceiver() {
        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Не был зарегистрирован
        }
    }

    // ─── Result ───────────────────────────────────────────────────────────────

    private fun finishWithResult() {
        val resultIntent = Intent().apply {
            putExtra(RESULT_PLAYLIST_TITLE, viewModel.playlistTitle())
            putExtra(RESULT_PLAYLIST_UPLOADER, viewModel.playlistUploader())
            putExtra(RESULT_PLAYLIST_COUNT, viewModel.items.value?.size ?: 0)
            putExtra(RESULT_PLAYLIST_URL, viewModel.playlistUrl())
            putExtra(RESULT_PLAYLIST_SUMMARY, viewModel.summaryText.value ?: "")
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}