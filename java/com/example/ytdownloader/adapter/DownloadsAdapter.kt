package com.example.ytdownloader.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ytdownloader.databinding.ItemDownloadBinding
import com.example.ytdownloader.model.DownloadedItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsAdapter(
    private val onOpenClicked: (DownloadedItem) -> Unit
) : ListAdapter<DownloadedItem, DownloadsAdapter.DownloadViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DownloadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DownloadViewHolder(
        private val binding: ItemDownloadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadedItem) {
            binding.tvDownloadTitle.text = item.title
            binding.tvDownloadFileName.text = item.fileName
            binding.tvDownloadMime.text = item.mimeType
            binding.tvDownloadDate.text = SimpleDateFormat(
                "dd.MM.yyyy HH:mm",
                Locale.getDefault()
            ).format(Date(item.timestamp))

            binding.btnOpen.setOnClickListener {
                onOpenClicked(item)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadedItem>() {
        override fun areItemsTheSame(oldItem: DownloadedItem, newItem: DownloadedItem): Boolean =
            oldItem.uriString == newItem.uriString

        override fun areContentsTheSame(oldItem: DownloadedItem, newItem: DownloadedItem): Boolean =
            oldItem == newItem
    }
}