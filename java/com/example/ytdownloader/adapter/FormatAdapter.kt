package com.example.ytdownloader.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ytdownloader.R
import com.example.ytdownloader.databinding.ItemFormatBinding
import com.example.ytdownloader.model.FormatItem

class FormatAdapter(
    private val onFormatSelected: (FormatItem) -> Unit
) : ListAdapter<FormatItem, FormatAdapter.FormatViewHolder>(FormatDiffCallback()) {

    private var selectedFormatId: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun setSelected(format: FormatItem?) {
        selectedFormatId = format?.formatId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FormatViewHolder {
        val binding = ItemFormatBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FormatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FormatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FormatViewHolder(
        private val binding: ItemFormatBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FormatItem) {
            binding.tvFormatQuality.text = item.qualityLabel
            binding.tvFormatDetails.text = item.displayName
            binding.tvFormatId.text = "ID: ${item.formatId}"

            binding.tvFormatType.text = when {
                item.isAudioOnly -> "Audio"
                item.isVideoOnly -> "Video"
                item.isMuxed -> "Muxed"
                else -> "?"
            }

            val badgeColor = when {
                item.isAudioOnly -> android.R.color.holo_green_dark
                item.isVideoOnly -> android.R.color.holo_blue_dark
                item.isMuxed -> android.R.color.holo_purple
                else -> android.R.color.darker_gray
            }
            binding.tvFormatType.setTextColor(
                ContextCompat.getColor(binding.root.context, badgeColor)
            )

            if (item.badgeText.isNotBlank()) {
                binding.tvFormatExtraBadge.visibility = android.view.View.VISIBLE
                binding.tvFormatExtraBadge.text = item.badgeText
            } else {
                binding.tvFormatExtraBadge.visibility = android.view.View.GONE
            }

            val isSelected = item.formatId == selectedFormatId
            binding.root.isSelected = isSelected
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (isSelected) R.color.selected_format_bg else R.color.format_bg
                )
            )

            binding.root.setOnClickListener {
                onFormatSelected(item)
            }
        }
    }

    class FormatDiffCallback : DiffUtil.ItemCallback<FormatItem>() {
        override fun areItemsTheSame(oldItem: FormatItem, newItem: FormatItem): Boolean =
            oldItem.formatId == newItem.formatId

        override fun areContentsTheSame(oldItem: FormatItem, newItem: FormatItem): Boolean =
            oldItem == newItem
    }
}