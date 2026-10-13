package com.cartube.tv

import android.content.ComponentName
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.cartube.R
import com.cartube.player.PlaybackService
import com.cartube.youtube.VideoItem
import com.google.common.util.concurrent.ListenableFuture

/**
 * Fullscreen player for the remote. With the controls hidden: OK pauses or plays,
 * left/right skip 10 seconds, up/down show the controls (quality, subtitles, speed).
 * Uses the same playback service as the phone, so history, positions, SponsorBlock,
 * subtitles and autoplay all work here too.
 */
class TvPlayerActivity : FragmentActivity() {

    private lateinit var playerView: PlayerView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pending: VideoItem? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.keepScreenOn = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@TvPlayerActivity, getString(R.string.playback_failed, error.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_player)
        playerView = findViewById(R.id.tvPlayerView)
        playerView.controllerShowTimeoutMs = 4000
        if (savedInstanceState == null) pending = VideoItem.fromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val ready = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = ready
            playerView.player = ready
            ready.addListener(listener)
            pending?.let {
                ready.setMediaItem(it.toMediaItem())
                ready.prepare()
                ready.play()
            }
            pending = null
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        // On TV, leaving the player stops playback (no picture-in-picture or background audio).
        controller?.pause()
        playerView.player = null
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = controller
        if (c != null && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (c.isPlaying) c.pause() else c.play()
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    c.seekTo((c.currentPosition - SEEK_MS).coerceAtLeast(0))
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    c.seekTo(c.currentPosition + SEEK_MS)
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    playerView.showController()
                    return true
                }
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private companion object {
        const val SEEK_MS = 10_000L
    }
}
