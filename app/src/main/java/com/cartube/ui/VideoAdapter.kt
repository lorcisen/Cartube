package com.cartube.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.cartube.R
import com.cartube.youtube.VideoItem

/**
 * Video list. The card style shows a full-width thumbnail like the YouTube home page; the
 * compact style puts the thumbnail beside the text and is used for "up next" lists.
 */
class VideoAdapter(
    private val compact: Boolean = false,
    private val showUploader: Boolean = true,
    private val onChannelClick: ((VideoItem) -> Unit)? = null,
    private val onClick: (VideoItem) -> Unit,
) : ListAdapter<VideoItem, VideoAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val frame: AspectRatioFrameLayout = view.findViewById(R.id.thumbnailFrame)
        val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        val duration: TextView = view.findViewById(R.id.duration)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val avatar: ImageView? = view.findViewById(R.id.avatar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (compact) R.layout.item_video_compact else R.layout.item_video_card
        val holder = Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        holder.frame.setAspectRatio(16f / 9f)
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        holder.title.text = item.title
        holder.meta.text = context.videoMeta(item, includeUploader = showUploader)
        holder.duration.text = formatDuration(item.durationSec)
        holder.duration.isVisible = item.durationSec > 0
        holder.thumbnail.load(item.thumbnail) {
            crossfade(true)
            placeholder(R.color.placeholder)
        }
        holder.avatar?.let { avatar ->
            avatar.isVisible = showUploader
            avatar.load(item.uploaderAvatar) {
                transformations(CircleCropTransformation())
                placeholder(R.drawable.avatar_placeholder)
                error(R.drawable.avatar_placeholder)
            }
            avatar.setOnClickListener { onChannelClick?.invoke(item) }
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    private object Diff : DiffUtil.ItemCallback<VideoItem>() {
        override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.url == b.url
        override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
    }
}
