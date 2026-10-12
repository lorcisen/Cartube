package com.cartube.ui

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cartube.R
import com.cartube.data.Library
import com.cartube.databinding.ActivityPlayerBinding
import com.cartube.player.PlaybackService
import com.cartube.youtube.VideoItem
import com.google.common.util.concurrent.ListenableFuture

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingItem: VideoItem? = null
    private var inCar = false

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateInfo()

        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePipParams()

        override fun onPlaybackStateChanged(playbackState: Int) {
            binding.loading.isVisible = playbackState == Player.STATE_BUFFERING
        }

        override fun onPlayerError(error: PlaybackException) {
            binding.loading.isVisible = false
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.playback_failed, error.message ?: error.errorCodeName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        if (savedInstanceState == null) pendingItem = VideoItem.fromIntent(intent)
        pendingItem?.let { showItem(it) }

        binding.favorite.setOnClickListener {
            val item = currentItem() ?: return@setOnClickListener
            val added = Library.toggleFavorite(item)
            updateFavorite(item.url)
            Toast.makeText(
                this,
                if (added) R.string.favorite_added else R.string.favorite_removed,
                Toast.LENGTH_SHORT,
            ).show()
        }

        CarConnection(this).type.observe(this) { type ->
            inCar = type == CarConnection.CONNECTION_TYPE_PROJECTION
            binding.carOverlay.isVisible = inCar
            updatePipParams()
        }

        applyOrientation(resources.configuration)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val item = VideoItem.fromIntent(intent) ?: return
        showItem(item)
        val ready = controller
        if (ready != null) play(ready, item) else pendingItem = item
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val ready = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = ready
            binding.playerView.player = ready
            ready.addListener(listener)
            pendingItem?.let { play(ready, it) }
            pendingItem = null
            updateInfo()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        binding.playerView.player = null
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        binding.playerView.useController = !isInPip
        binding.info.isVisible = !isInPip && newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Older Android versions have no auto-enter, so request picture-in-picture here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && canUsePip()) {
            enterPictureInPictureMode(pipParams())
        }
    }

    private fun play(controller: MediaController, item: VideoItem) {
        binding.loading.isVisible = true
        controller.setMediaItem(item.toMediaItem())
        controller.prepare()
        controller.play()
    }

    private fun currentItem(): VideoItem? =
        controller?.currentMediaItem?.let { VideoItem.fromMediaItem(it) } ?: pendingItem

    private fun showItem(item: VideoItem) {
        binding.title.text = item.title
        binding.uploader.text = item.uploader
        updateFavorite(item.url)
    }

    private fun updateInfo() {
        currentItem()?.let { showItem(it) }
        updatePipParams()
    }

    private fun updateFavorite(url: String) {
        binding.favorite.setImageResource(
            if (Library.isFavorite(url)) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
        )
    }

    private fun applyOrientation(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.info.isVisible = !landscape
        binding.playerContainer.layoutParams = binding.playerContainer.layoutParams.apply {
            height = if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (landscape) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Picture-in-picture would put video back on screen, so it is off while in the car. */
    private fun canUsePip() = !inCar && controller?.isPlaying == true

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(canUsePip())
        return builder.build()
    }

    private fun updatePipParams() {
        setPictureInPictureParams(pipParams())
    }
}
