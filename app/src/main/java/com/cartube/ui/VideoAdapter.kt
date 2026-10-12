package com.cartube.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.cartube.databinding.ItemVideoBinding
import com.cartube.youtube.VideoItem

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit,
) : ListAdapter<VideoItem, VideoAdapter.Holder>(Diff) {

    class Holder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            title.text = item.title
            uploader.text = item.uploader
            duration.text = formatDuration(item.durationSec)
            thumbnail.load(item.thumbnail) { crossfade(true) }
            root.setOnClickListener { onClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.url == b.url
        override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
    }
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
