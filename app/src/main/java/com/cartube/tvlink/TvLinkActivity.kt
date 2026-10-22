package com.cartube.tvlink

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cartube.R
import com.cartube.data.Settings
import com.cartube.player.YoutubeMediaSourceFactory
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

/**
 * Shows, without sound, the video a connected phone is playing, kept in step with the
 * phone's audio. Small differences are evened out by playing slightly faster or slower;
 * large ones (a seek, a new video) by jumping ahead and waiting for the phone to catch up.
 * The remote's OK and left/right control the phone.
 */
class TvLinkActivity : FragmentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var loadedUrl: String? = null
    private var loadJob: Job? = null

    /** Paused on purpose after jumping ahead, until the phone reaches the same spot. */
    private var holding = false

    /** How far ahead to jump; grows when loading takes longer than the jump. */
    private var leadMs = 1500L
    private var wasConnected = false

    private val tick = object : Runnable {
        override fun run() {
            sync()
            handler.postDelayed(this, 100)
        }
    }
    private val hideStatus = Runnable { status.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_link)
        playerView = findViewById(R.id.linkPlayerView)
        status = findViewById(R.id.linkStatus)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(YoutubeMediaSourceFactory(this))
            .build()
        player.volume = 0f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            // The sound comes from the phone; don't even download it here.
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setPreferredTextLanguage(if (Settings.subtitles == Settings.SUBTITLES_SWEDISH) "sv" else null)
            .setIgnoredTextSelectionFlags(if (Settings.subtitles == Settings.SUBTITLES_OFF) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
        playerView.player = player
        playerView.useController = false
        playerView.keepScreenOn = true

        showStatus(getString(R.string.tv_link_waiting), hide = false)
        lifecycleScope.launch {
            TvLinkServer.phone.collect { phone ->
                if (phone != null) {
                    wasConnected = true
                } else if (wasConnected) {
                    showStatus(getString(R.string.tv_link_phone_left), hide = false)
                    handler.postDelayed({ finish() }, 2500)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        handler.post(tick)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(tick)
        player.pause()
        if (!isChangingConfigurations) {
            TvLinkServer.close()
            finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val command = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> JSONObject().put("c", "toggle")
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> JSONObject().put("c", "seek").put("by", -SEEK_MS)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> JSONObject().put("c", "seek").put("by", SEEK_MS)
                else -> null
            }
            if (command != null) {
                TvLinkServer.send(command.put("t", "cmd"))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun sync() {
        val state = TvLinkServer.state.value
        val video = state?.video
        if (state == null || video == null) {
            player.playWhenReady = false
            return
        }
        if (video.url != loadedUrl) return load(video)
        if (loadJob?.isActive == true || player.mediaItemCount == 0) return

        val position = player.currentPosition
        if (!state.playing) {
            holding = false
            player.playWhenReady = false
            if (abs(position - state.positionMs) > 250) player.seekTo(state.positionMs)
            return
        }

        val expected = state.positionAt(SystemClock.elapsedRealtime())
        when {
            holding -> {
                if (player.playbackState != Player.STATE_READY) return
                val wait = position - expected
                when {
                    // Loading took longer than the jump: the phone is already past us.
                    wait < -400 -> {
                        leadMs = (leadMs + 1000).coerceAtMost(8000)
                        jumpAhead(expected)
                    }
                    wait <= 15 -> {
                        holding = false
                        setSpeed(state.speed)
                        player.play()
                    }
                }
            }
            player.playbackState == Player.STATE_BUFFERING -> Unit
            player.playbackState == Player.STATE_ENDED -> Unit
            abs(position - expected) > 1500 -> jumpAhead(expected)
            else -> {
                // Positive: the picture is ahead of the sound, so slow down a little.
                val drift = position - expected
                val correction = if (abs(drift) < 30) 0f else (drift / 2000f).coerceIn(-0.08f, 0.08f)
                setSpeed(state.speed * (1f - correction))
                player.playWhenReady = true
            }
        }
    }

    private fun jumpAhead(expected: Long) {
        holding = true
        player.playWhenReady = false
        player.seekTo(expected + leadMs)
    }

    private fun setSpeed(speed: Float) {
        if (abs(player.playbackParameters.speed - speed) > 0.002f) player.setPlaybackSpeed(speed)
    }

    private fun load(video: VideoItem) {
        loadedUrl = video.url
        holding = false
        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        showStatus("${video.title}\n${getString(R.string.tv_link_sound_on_phone)}", hide = true)
        loadJob = lifecycleScope.launch {
            val loaded = runCatching { YouTube.streamInfo(video.url) }
            if (loaded.isFailure) {
                showStatus(getString(R.string.tv_link_load_failed), hide = true)
                return@launch
            }
            player.setMediaItem(video.toMediaItem())
            player.prepare()
            val state = TvLinkServer.state.value
            if (state?.playing == true) {
                jumpAhead(state.positionAt(SystemClock.elapsedRealtime()))
            } else {
                player.seekTo(state?.positionMs ?: 0)
            }
        }
    }

    private fun showStatus(text: String, hide: Boolean) {
        status.text = text
        status.visibility = View.VISIBLE
        handler.removeCallbacks(hideStatus)
        if (hide) handler.postDelayed(hideStatus, 5000)
    }

    companion object {
        @Volatile
        var visible = false
            private set

        private const val SEEK_MS = 10_000L
    }
}
