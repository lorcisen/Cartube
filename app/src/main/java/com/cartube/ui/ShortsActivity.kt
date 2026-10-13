package com.cartube.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.cartube.R
import com.cartube.data.Library
import com.cartube.databinding.ActivityShortsBinding
import com.cartube.databinding.ItemShortBinding
import com.cartube.player.YoutubeMediaSourceFactory
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A vertical, swipeable Shorts feed. One player is moved into whichever page is showing;
 * each Short loops until you swipe on.
 */
class ShortsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShortsBinding
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private val items = ArrayList<VideoItem>()
    private val adapter = ShortsAdapter()
    private var current = -1
    private var playJob: Job? = null
    private var loadingMore = false
    private var inCar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityShortsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(YoutubeMediaSourceFactory(this))
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                // Shorts are watched without subtitles.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            player = this@ShortsActivity.player
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            subtitleView?.visibility = android.view.View.GONE
            keepScreenOn = true
        }

        binding.back.setOnClickListener { finish() }
        binding.pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        binding.pager.adapter = adapter
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                playAt(position)
                if (position >= items.size - 3) loadMore()
            }
        })

        // Shorts are video: in the car nothing is shown or played.
        CarConnection(this).type.observe(this) { type ->
            inCar = type == CarConnection.CONNECTION_TYPE_PROJECTION
            binding.carOverlay.isVisible = inCar
            if (inCar) player.pause()
        }
        loadFirst()
    }

    override fun onStop() {
        player.pause()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (current >= 0 && !inCar) player.play()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private fun loadFirst() {
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val subscriptions = Library.subscriptions()
            val found = runCatching {
                if (subscriptions.isNotEmpty()) YouTube.shorts(subscriptions) else emptyList()
            }.getOrDefault(emptyList()).ifEmpty {
                runCatching { YouTube.searchShorts("#shorts") }.getOrDefault(emptyList())
            }
            binding.progress.isVisible = false
            if (found.isEmpty()) {
                binding.message.isVisible = true
                return@launch
            }
            add(found)
        }
    }

    /** More Shorts from searches on the channels you watch, so the feed keeps going. */
    private fun loadMore() {
        if (loadingMore) return
        loadingMore = true
        lifecycleScope.launch {
            val seeds = (Library.history().map { it.uploader } + Library.subscriptions().map { it.name })
                .filter { it.isNotBlank() }.distinct()
            val query = (seeds.randomOrNull() ?: "") + " #shorts"
            val more = runCatching { YouTube.searchShorts(query) }.getOrDefault(emptyList())
            add(more)
            loadingMore = false
        }
    }

    private fun add(more: List<VideoItem>) {
        val known = items.map { it.url }.toSet()
        val fresh = more.filter { it.url !in known && !Library.isHidden(it) }
        if (fresh.isEmpty()) return
        val start = items.size
        items += fresh
        adapter.notifyItemRangeInserted(start, fresh.size)
        if (current < 0) playAt(0)
    }

    private fun playAt(position: Int) {
        if (position !in items.indices) return
        current = position
        val holder = (binding.pager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(position)
            as? ShortsAdapter.Holder
        holder?.attachPlayer()
        val item = items[position]
        player.stop()
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            val ok = runCatching { YouTube.streamInfo(item.url) }.isSuccess
            if (!ok || current != position) {
                if (!ok) Toast.makeText(this@ShortsActivity, R.string.short_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            player.setMediaItem(item.toMediaItem())
            player.prepare()
            if (!inCar) player.play()
        }
    }

    private inner class ShortsAdapter : RecyclerView.Adapter<ShortsAdapter.Holder>() {
        inner class Holder(val b: ItemShortBinding) : RecyclerView.ViewHolder(b.root) {
            fun attachPlayer() {
                (playerView.parent as? ViewGroup)?.removeView(playerView)
                b.playerSlot.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemShortBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.b.thumbnail.load(item.thumbnail)
            holder.b.title.text = item.title
            holder.b.channel.text = item.uploader
            holder.b.channel.setOnClickListener { openChannelOf(item) }
            holder.b.share.setOnClickListener { shareVideo(item) }
            holder.b.favorite.setOnClickListener {
                val added = Library.toggleFavorite(item)
                Toast.makeText(this@ShortsActivity, if (added) R.string.favorite_added else R.string.favorite_removed, Toast.LENGTH_SHORT).show()
            }
            holder.b.root.setOnClickListener {
                if (player.isPlaying) player.pause() else if (!inCar) player.play()
            }
            if (position == current) holder.attachPlayer()
        }

        override fun onViewAttachedToWindow(holder: Holder) {
            if (holder.bindingAdapterPosition == current) holder.attachPlayer()
        }
    }
}
