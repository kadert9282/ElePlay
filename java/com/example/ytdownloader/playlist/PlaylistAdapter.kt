package com.example.ytdownloader.playlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.ytdownloader.R
import com.example.ytdownloader.model.PlaylistItem

class PlaylistAdapter(
    private val onToggleSelected: (Int) -> Unit
) : ListAdapter<PlaylistItem, PlaylistAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlaylistItem>() {
            override fun areItemsTheSame(a: PlaylistItem, b: PlaylistItem): Boolean =
                a.id == b.id && a.index == b.index

            override fun areContentsTheSame(a: PlaylistItem, b: PlaylistItem): Boolean =
                a == b
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        val tvState: TextView = itemView.findViewById(R.id.tvState)
        val tvDownloadSpeed: TextView = itemView.findViewById(R.id.tvDownloadSpeed)
        val ivDone: ImageView = itemView.findViewById(R.id.ivDone)
        val ivFailed: ImageView = itemView.findViewById(R.id.ivFailed)
        val ivSkipped: ImageView = itemView.findViewById(R.id.ivSkipped)
        val progressDownloading: ProgressBar = itemView.findViewById(R.id.progressDownloading)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_video, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)

        holder.tvIndex.text = (item.index + 1).toString()
        holder.tvTitle.text = item.title
        holder.tvDuration.text = item.durationFormatted

        // Thumbnail
        if (!item.thumbnail.isNullOrBlank()) {
            holder.ivThumbnail.load(item.thumbnail) { crossfade(true) }
        } else {
            holder.ivThumbnail.setImageDrawable(null)
        }

        // ─── Чекбокс ──────────────────────────────────────────────────────────
        //
        // Правило: чекбокс ЗАБЛОКИРОВАН только во время активного скачивания
        // этого элемента (DOWNLOADING) или пока он в очереди (QUEUED).
        //
        // DONE — НЕ блокирует чекбокс: пользователь может поставить галку
        // чтобы скачать заново (в другом качестве или если удалил файл).

        val isActivelyDownloading =
            item.downloadState == PlaylistItem.DownloadState.DOWNLOADING ||
                    item.downloadState == PlaylistItem.DownloadState.QUEUED

        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = item.isSelected
        holder.cbSelect.isEnabled = !isActivelyDownloading
        holder.cbSelect.setOnCheckedChangeListener { _, _ ->
            onToggleSelected(item.index)
        }

        // Весь элемент тоже кликабельный
        holder.itemView.setOnClickListener {
            if (!isActivelyDownloading) {
                onToggleSelected(item.index)
            }
        }

        // ─── Состояние ────────────────────────────────────────────────────────

        // Сбрасываем всё перед применением нового состояния
        holder.ivDone.visibility = View.GONE
        holder.ivFailed.visibility = View.GONE
        holder.ivSkipped.visibility = View.GONE
        holder.progressDownloading.visibility = View.GONE
        holder.tvDownloadSpeed.visibility = View.GONE
        holder.tvState.visibility = View.VISIBLE

        when (item.downloadState) {
            PlaylistItem.DownloadState.NONE -> {
                holder.tvState.text = buildStateText(item)
                holder.tvState.visibility =
                    if (!item.statusText.isNullOrBlank()) View.VISIBLE else View.GONE
                holder.itemView.alpha = 1.0f
            }

            PlaylistItem.DownloadState.QUEUED -> {
                holder.tvState.text = "Queued"
                holder.itemView.alpha = 1.0f
            }

            PlaylistItem.DownloadState.DOWNLOADING -> {
                holder.tvState.text = "Downloading"
                holder.progressDownloading.visibility = View.VISIBLE

                if (!item.statusText.isNullOrBlank()) {
                    holder.tvDownloadSpeed.text = item.statusText
                    holder.tvDownloadSpeed.visibility = View.VISIBLE
                }
                holder.itemView.alpha = 1.0f
            }

            PlaylistItem.DownloadState.DONE -> {
                holder.ivDone.visibility = View.VISIBLE
                holder.itemView.alpha = if (item.isSelected) 1.0f else 0.85f

                // isSelected = true → пользователь хочет скачать заново
                holder.tvState.text = if (item.isSelected) {
                    "Will re-download"
                } else {
                    item.statusText ?: "Downloaded"
                }
            }

            PlaylistItem.DownloadState.FAILED -> {
                holder.ivFailed.visibility = View.VISIBLE
                holder.tvState.text = buildString {
                    append("Failed")
                    if (!item.errorMessage.isNullOrBlank()) {
                        append(": ")
                        append(item.errorMessage.take(60))
                    }
                }
                holder.itemView.alpha = 1.0f
            }

            PlaylistItem.DownloadState.SKIPPED -> {
                holder.ivSkipped.visibility = View.VISIBLE
                holder.tvState.text = "Skipped"
                holder.itemView.alpha = 0.55f
            }
        }
    }

    private fun buildStateText(item: PlaylistItem): String {
        if (!item.statusText.isNullOrBlank()) return item.statusText
        return if (item.isSelected) "Selected" else "Not selected"
    }
}