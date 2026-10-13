package com.cartube.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import coil.load
import coil.transform.CircleCropTransformation
import com.cartube.R
import com.cartube.data.Library
import com.cartube.notify.ChannelBells
import com.cartube.data.VideoSort
import com.cartube.databinding.ActivityChannelBinding
import com.cartube.databinding.ChannelHeaderBinding
import com.cartube.youtube.Channel
import com.cartube.youtube.YouTube
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

class ChannelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelBinding
    private lateinit var channel: Channel
    private var subscribers = -1L
    private var banner: String? = null

    private val header = HeaderAdapter()
    private val videos = VideoAdapter(showUploader = false) { openVideo(it) }
    private var allVideos: List<com.cartube.youtube.VideoItem> = emptyList()
    private var sort = VideoSort.NEWEST
    private var videosTab: ListLinkHandler? = null
    private var nextPage: Page? = null
    private var loadingMore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyAmoledIfEnabled()
        binding = ActivityChannelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        channel = Channel.fromIntent(intent) ?: return finish()
        binding.toolbar.title = channel.name
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.menu.add(R.string.sort).setIcon(R.drawable.ic_sort)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        binding.toolbar.setOnMenuItemClickListener {
            pickSort(listOf(VideoSort.NEWEST, VideoSort.OLDEST, VideoSort.MOST_VIEWED, VideoSort.LONGEST, VideoSort.SHORTEST), sort) {
                sort = it
                videos.submitList(sort.apply(allVideos))
            }
            true
        }
        binding.list.adapter = ConcatAdapter(header, videos)
        binding.list.useGrid { it == 0 }
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) loadMoreIfNearEnd()
            }
        })
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
                allVideos = page.videos
                videosTab = page.videosTab
                nextPage = page.nextPage
                videos.submitList(sort.apply(allVideos)) { loadMoreIfNearEnd() }
            }.onFailure {
                binding.error.isVisible = true
                binding.error.text = getString(R.string.channel_failed, describeError(it))
            }
        }
    }

    /** Fetches the channel's next page of videos once the list is scrolled close to its end. */
    private fun loadMoreIfNearEnd() {
        val tab = videosTab ?: return
        val page = nextPage ?: return
        if (loadingMore) return
        val lm = binding.list.layoutManager as? LinearLayoutManager ?: return
        if (lm.findLastVisibleItemPosition() < lm.itemCount - 8) return
        loadingMore = true
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val result = runCatching { YouTube.channelMore(tab, page, channel) }
            binding.progress.isVisible = false
            result.onSuccess { (more, next) ->
                val known = allVideos.mapTo(HashSet()) { it.url }
                allVideos = allVideos + more.filter { it.url !in known }
                nextPage = next
                videos.submitList(sort.apply(allVideos)) {
                    loadingMore = false
                    loadMoreIfNearEnd()
                }
            }.onFailure {
                loadingMore = false
                Toast.makeText(this@ChannelActivity, describeError(it), Toast.LENGTH_SHORT).show()
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
            fun renderBell() {
                val on = ChannelBells.isOn(channel.url)
                b.bell.setIconResource(if (on) R.drawable.ic_bell else R.drawable.ic_bell_off)
                b.bell.isVisible = Library.isSubscribed(channel.url)
            }
            renderBell()
            b.subscribe.addOnCheckedChangeListener { _, _ -> renderBell() }
            b.bell.setOnClickListener {
                val on = ChannelBells.toggle(channel.url)
                renderBell()
                android.widget.Toast.makeText(this@ChannelActivity, if (on) R.string.bell_on else R.string.bell_off, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
