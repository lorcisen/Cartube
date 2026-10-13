package com.cartube.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.cartube.R
import com.cartube.data.Settings
import com.cartube.net.SponsorBlock
import com.cartube.youtube.videoIdOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Skips SponsorBlock segments of whatever [player] is playing. */
class SegmentSkipper(
    private val context: Context,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var player: Player? = null
    private var segments: List<SponsorBlock.Segment> = emptyList()
    private var segmentsFor: String? = null
    private var loadJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 400)
        }
    }

    fun attach(target: Player) {
        player?.removeListener(this)
        player = target
        target.addListener(this)
        target.currentMediaItem?.let { load(it) }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun release() {
        handler.removeCallbacks(tick)
        player?.removeListener(this)
        player = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItem?.let { load(it) }
    }

    private fun load(item: MediaItem) {
        val id = videoIdOf(item.mediaId) ?: return
        if (id == segmentsFor) return
        segments = emptyList()
        segmentsFor = id
        loadJob?.cancel()
        if (!Settings.sponsorBlock) return
        loadJob = scope.launch {
            val found = runCatching { SponsorBlock.segments(id, Settings.sponsorCategories) }
                .getOrDefault(emptyList())
            if (segmentsFor == id) segments = found
        }
    }

    private fun check() {
        val p = player ?: return
        if (segments.isEmpty() || !p.isPlaying) return
        val position = p.currentPosition
        val segment = segments.firstOrNull { position >= it.startMs && position < it.endMs - 500 } ?: return
        val duration = p.duration
        // A segment that runs to the very end would just end the video; leave those alone.
        if (duration > 0 && segment.endMs >= duration - 1000) return
        p.seekTo(segment.endMs)
        Toast.makeText(context, context.getString(R.string.skipped_segment, label(segment.category)), Toast.LENGTH_SHORT).show()
    }

    private fun label(category: String) = context.getString(
        when (category) {
            "sponsor" -> R.string.sb_sponsor
            "selfpromo" -> R.string.sb_selfpromo
            "interaction" -> R.string.sb_interaction
            "intro" -> R.string.sb_intro
            "outro" -> R.string.sb_outro
            "preview" -> R.string.sb_preview
            "music_offtopic" -> R.string.sb_music_offtopic
            "filler" -> R.string.sb_filler
            else -> R.string.sb_sponsor
        },
    )
}
