package com.cartube.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.cartube.R
import com.cartube.data.Library
import com.cartube.data.VideoSort
import com.cartube.databinding.ActivityPlaylistBinding
import com.cartube.player.PlayQueue
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** A local playlist, a saved YouTube playlist or a YouTube playlist from search. */
class PlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistBinding
    private var videos: List<VideoItem> = emptyList()
    private var name = ""
    private var sort = VideoSort.ORIGINAL
    private val localId get() = intent.getStringExtra(EXTRA_LOCAL_ID)
    private val remoteUrl get() = intent.getStringExtra(EXTRA_URL)

    private val adapter = VideoAdapter(
        compact = true,
        onLongClick = { onLongPress(it) },
        onClick = { play(startAt = it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyAmoledIfEnabled()
        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.adapter = adapter
        binding.list.useGrid(minItemWidthDp = 380)
        binding.playAll.setOnClickListener { play(shuffle = false) }
        binding.shuffle.setOnClickListener { play(shuffle = true) }
        binding.save.setOnClickListener {
            val url = remoteUrl ?: return@setOnClickListener
            Library.saveYoutubePlaylist(url, name)
            binding.save.isVisible = false
            Toast.makeText(this, R.string.playlist_saved, Toast.LENGTH_SHORT).show()
        }
        binding.toolbar.inflateMenu(R.menu.playlist)
        binding.toolbar.menu.findItem(R.id.sort).setOnMenuItemClickListener {
            pickSort(
                listOf(VideoSort.ORIGINAL, VideoSort.NEWEST, VideoSort.OLDEST, VideoSort.MOST_VIEWED, VideoSort.TITLE, VideoSort.LONGEST, VideoSort.SHORTEST),
                sort,
                { getString(if (it == VideoSort.ORIGINAL) R.string.sort_playlist_order else it.label) },
            ) {
                sort = it
                adapter.submitList(sort.apply(videos))
            }
            true
        }
        binding.toolbar.menu.findItem(R.id.rename).isVisible = localId != null
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.rename -> VideoActions.askName(this, name) { newName ->
                    val id = localId ?: return@askName
                    val playlist = Library.playlist(id) ?: return@askName
                    Library.deletePlaylist(id)
                    val renamed = Library.createPlaylist(newName)
                    playlist.videos.forEach { Library.addToPlaylist(renamed.id, it) }
                    intent.putExtra(EXTRA_LOCAL_ID, renamed.id)
                    load()
                }
                R.id.delete -> MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.playlist_delete)
                    .setPositiveButton(R.string.playlist_delete) { _, _ ->
                        val saved = localId ?: Library.playlists().firstOrNull { it.sourceUrl == remoteUrl }?.id
                        saved?.let { Library.deletePlaylist(it) }
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        load()
    }

    private fun load() {
        val id = localId
        if (id != null) {
            val playlist = Library.playlist(id) ?: return finish()
            if (playlist.sourceUrl != null) {
                intent.putExtra(EXTRA_URL, playlist.sourceUrl)
                intent.removeExtra(EXTRA_LOCAL_ID)
                return load()
            }
            show(playlist.name, "", playlist.videos.firstOrNull()?.thumbnail, playlist.videos)
            binding.save.isVisible = false
            binding.toolbar.menu.findItem(R.id.delete).isVisible = true
            return
        }
        val url = remoteUrl ?: return finish()
        val saved = Library.playlists().any { it.sourceUrl == url }
        binding.save.isVisible = !saved
        binding.toolbar.menu.findItem(R.id.delete).isVisible = saved
        binding.progress.isVisible = true
        lifecycleScope.launch {
            val result = runCatching { YouTube.playlist(url) }
            binding.progress.isVisible = false
            result.onSuccess { show(it.name, it.uploader, it.thumbnail, it.videos) }
                .onFailure {
                    binding.error.isVisible = true
                    binding.error.text = getString(R.string.playlist_failed, it.message ?: it.javaClass.simpleName)
                }
        }
    }

    private fun show(title: String, uploader: String, thumbnail: String?, items: List<VideoItem>) {
        name = title
        videos = items
        binding.toolbar.title = title
        binding.name.text = title
        binding.meta.text = listOfNotNull(uploader.takeIf { it.isNotBlank() }, getString(R.string.videos_count, items.size))
            .joinToString(" · ")
        binding.cover.load(thumbnail)
        adapter.submitList(sort.apply(items))
        binding.empty.isVisible = items.isEmpty()
    }

    /** Plays from [startAt] (or the start); the rest of the playlist goes into the queue. */
    private fun play(shuffle: Boolean = false, startAt: VideoItem? = null) {
        if (videos.isEmpty()) return
        val order = if (shuffle) videos.shuffled() else sort.apply(videos)
        val start = startAt?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        PlayQueue.replace(order.drop(start + 1))
        openVideo(order[start])
    }

    private fun onLongPress(item: VideoItem) {
        val id = localId
        if (id == null) {
            VideoActions.show(this, item)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf(getString(R.string.remove_from_playlist), getString(R.string.more_options))) { _, which ->
                if (which == 0) {
                    Library.removeFromPlaylist(id, item.url)
                    load()
                } else {
                    VideoActions.show(this, item)
                }
            }
            .show()
    }

    companion object {
        private const val EXTRA_LOCAL_ID = "local_id"
        private const val EXTRA_URL = "url"

        fun local(context: Context, id: String) =
            context.startActivity(Intent(context, PlaylistActivity::class.java).putExtra(EXTRA_LOCAL_ID, id))

        fun remote(context: Context, url: String) =
            context.startActivity(Intent(context, PlaylistActivity::class.java).putExtra(EXTRA_URL, url))
    }
}
