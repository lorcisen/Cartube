package com.cartube.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import coil.load
import coil.transform.CircleCropTransformation
import com.cartube.R
import com.cartube.data.Library
import com.cartube.databinding.ActivityChannelBinding
import com.cartube.databinding.ChannelHeaderBinding
import com.cartube.youtube.Channel
import com.cartube.youtube.YouTube
import kotlinx.coroutines.launch

class ChannelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelBinding
    private lateinit var channel: Channel
    private var subscribers = -1L
    private var banner: String? = null

    private val header = HeaderAdapter()
    private val videos = VideoAdapter(showUploader = false) { openVideo(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityChannelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        channel = Channel.fromIntent(intent) ?: return finish()
        binding.toolbar.title = channel.name
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.adapter = ConcatAdapter(header, videos)
        load()
    }

    private fun load() {
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val result = runCatching { YouTube.channel(channel.url) }
            binding.progress.isVisible = false
            result.onSuccess { page ->
                channel = page.channel
                subscribers = page.subscribers
                banner = page.banner
                Library.updateChannel(channel)
                binding.toolbar.title = channel.name
                header.notifyItemChanged(0)
                videos.submitList(page.videos)
            }.onFailure {
                binding.error.isVisible = true
                binding.error.text = getString(R.string.channel_failed, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.Holder>() {
        inner class Holder(val b: ChannelHeaderBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ChannelHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val b = holder.b
            b.name.text = channel.name
            b.subscribers.isVisible = subscribers >= 0
            b.subscribers.text = getString(R.string.subscribers, formatCount(subscribers))
            b.banner.isVisible = banner != null
            b.banner.load(banner)
            b.avatar.load(channel.avatar) {
                transformations(CircleCropTransformation())
                placeholder(R.drawable.avatar_placeholder)
                error(R.drawable.avatar_placeholder)
            }
            bindSubscribeButton(b.subscribe, channel)
        }
    }
}
