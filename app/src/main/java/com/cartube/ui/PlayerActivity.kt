package com.cartube.ui

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.cartube.R
import com.cartube.data.Library
import com.cartube.databinding.ActivityPlayerBinding
import com.cartube.databinding.PlayerHeaderBinding
import com.cartube.data.Settings as AppSettings
import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import com.cartube.net.Dislikes
import com.cartube.player.PlayQueue
import com.cartube.player.PlaybackService
import org.schabi.newpipe.extractor.stream.StreamSegment
import com.cartube.player.YoutubeMediaSourceFactory
import com.cartube.youtube.StreamCache
import com.cartube.youtube.videoIdOf
import androidx.core.os.bundleOf
import androidx.media3.session.SessionCommand
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.cartube.youtube.Channel
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import com.cartube.youtube.YouTube.toVideoItem
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.stream.Description

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingItem: VideoItem? = null
    private var inCar = false

    /** What the details below the player currently show. */
    private var shown: VideoItem? = null
    private var subscribers = -1L
    private var description: CharSequence? = null
    private var votes: String? = null
    private var chapters: List<StreamSegment> = emptyList()
    private var commentsLabel: String? = null
    private var descriptionExpanded = false
    private var detailsJob: Job? = null

    private val header = HeaderAdapter()
    private val related = VideoAdapter(
        compact = true,
        onClick = { playNew(it) },
    )

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let { showFromPlayer(it) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Keep the screen on while a video plays; let it sleep when paused.
            binding.playerView.keepScreenOn = isPlaying
            updatePipParams()
        }

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

    /** Leaves fullscreen on back instead of closing the player. */
    private val exitFullscreenOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullscreen(false)
    }

    /**
     * After the fullscreen button locks the orientation, unlock it again once the phone is
     * physically turned the same way, so rotating keeps working as usual afterwards.
     */
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN || !autoRotateEnabled()) return
                val portrait = degrees < 30 || degrees > 330
                val landscape = degrees in 60..120 || degrees in 240..300
                val locked = requestedOrientation
                if ((locked == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT && portrait) ||
                    (locked == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE && landscape)
                ) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyAmoledIfEnabled()
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Normally keep clear of the system bars and the camera cutout; in fullscreen use
        // every pixel, edge to edge.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            if (fullscreenNow) {
                v.setPadding(0, 0, 0, 0)
            } else {
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onBackPressedDispatcher.addCallback(this, exitFullscreenOnBack)

        binding.details.adapter = ConcatAdapter(header, related)
        binding.playerView.setFullscreenButtonClickListener { setFullscreen(it) }
        PlayerGestures(this, binding.playerView, binding.gestureIndicator) { controller }.attach()
        styleSubtitles()
        runCatching { CastButtonFactory.setUpMediaRouteButton(applicationContext, binding.castButton) }

        if (savedInstanceState == null) pendingItem = VideoItem.fromIntent(intent)
        pendingItem?.let { show(it) }

        CarConnection(this).type.observe(this) { type ->
            inCar = type == CarConnection.CONNECTION_TYPE_PROJECTION
            binding.carOverlay.isVisible = inCar
            updatePipParams()
        }

        applyOrientation(resources.configuration)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        VideoItem.fromIntent(intent)?.let { playNew(it) }
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
            binding.playerView.keepScreenOn = ready.isPlaying
            val pending = pendingItem
            pendingItem = null
            if (pending != null) play(ready, pending) else ready.currentMediaItem?.let { showFromPlayer(it) }
            updatePipParams()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
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
        binding.castButton.isVisible = !isInPip
        applyOrientation(newConfig)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Older Android versions have no auto-enter, so request picture-in-picture here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && canUsePip()) {
            enterPictureInPictureMode(pipParams())
        }
    }

    private fun playNew(item: VideoItem) {
        show(item)
        val ready = controller
        if (ready != null) play(ready, item) else pendingItem = item
        binding.details.scrollToPosition(0)
    }

    private fun play(controller: MediaController, item: VideoItem) {
        binding.loading.isVisible = true
        controller.setMediaItem(item.toMediaItem())
        controller.prepare()
        controller.play()
    }

    private fun showFromPlayer(mediaItem: MediaItem) {
        if (mediaItem.mediaId != shown?.url) show(VideoItem.fromMediaItem(mediaItem))
    }

    /** Shows what we know right away, then loads description, channel and related videos. */
    private fun show(item: VideoItem) {
        val sameVideo = shown?.url == item.url
        shown = item
        if (!sameVideo) {
            subscribers = -1
            description = null
            votes = null
            chapters = emptyList()
            commentsLabel = null
            binding.playerView.setExtraAdGroupMarkers(null, null)
            descriptionExpanded = false
            related.submitList(emptyList())
        }
        header.notifyItemChanged(0)
        if (sameVideo) return

        detailsJob?.cancel()
        detailsJob = lifecycleScope.launch {
            val result = runCatching { YouTube.streamInfo(item.url) }
            val failure = result.exceptionOrNull()
            if (failure is ContentNotAvailableException && shown?.url == item.url) {
                // Members-only, private, removed…: say why instead of a spinner that never ends.
                binding.loading.isVisible = false
                val message = describeError(failure)
                Toast.makeText(
                    this@PlayerActivity,
                    if (failure.javaClass == ContentNotAvailableException::class.java) getString(R.string.error_unavailable, message) else message,
                    Toast.LENGTH_LONG,
                ).show()
            }
            val info = result.getOrNull() ?: return@launch
            if (shown?.url != item.url && shown?.url != info.url) return@launch
            shown = info.toVideoItem()
            subscribers = info.uploaderSubscriberCount
            description = info.description?.let { renderDescription(it) }
            chapters = info.streamSegments.orEmpty()
            // Chapter starts as markers on the time bar.
            val marks = chapters.map { it.startTimeSeconds * 1000L }.filter { it > 0 }.toLongArray()
            binding.playerView.setExtraAdGroupMarkers(marks, BooleanArray(marks.size))
            header.notifyItemChanged(0)
            related.submitList(YouTube.related(info))

            val id = videoIdOf(info.url)
            val fetched = if (AppSettings.showDislikes && id != null) Dislikes.votes(id) else null
            if (shown?.url != info.url) return@launch
            val likes = info.likeCount.takeIf { it >= 0 } ?: fetched?.likes?.takeIf { it >= 0 }
            val dislikes = fetched?.dislikes?.takeIf { it >= 0 }
            votes = listOfNotNull(
                likes?.let { getString(R.string.likes, formatCount(it)) },
                dislikes?.let { getString(R.string.dislikes, formatCount(it)) },
            ).joinToString("   ").ifBlank { null }
            header.notifyItemChanged(0)
        }
    }

    /** White text on a dark box: readable on any picture, like YouTube's own captions. */
    private fun styleSubtitles() {
        binding.playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE,
                    0xB3000000.toInt(),
                    Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_NONE,
                    Color.TRANSPARENT,
                    null,
                ),
            )
            setFractionalTextSize(AppSettings.subtitleSize)
            setBottomPaddingFraction(0.06f)
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        styleSubtitles()
    }

    private fun showQueue() {
        val upcoming = PlayQueue.upcoming()
        if (upcoming.isEmpty()) {
            Toast.makeText(this, R.string.queue_empty, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.queue)
            .setItems(upcoming.map { it.title }.toTypedArray()) { _, which ->
                // Jump to that video; the ones before it stay queued.
                val chosen = upcoming[which]
                PlayQueue.remove(chosen.url)
                playNew(chosen)
            }
            .setNeutralButton(R.string.queue_clear) { _, _ -> PlayQueue.clear() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun pickQuality() {
        val item = shown ?: return
        val info = StreamCache.get(item.url)
        val heights = info?.let { YoutubeMediaSourceFactory.availableHeights(it) }.orEmpty()
        if (heights.isEmpty()) {
            Toast.makeText(this, R.string.quality_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val current = AppSettings.maxHeightFor(item.url)
        val selected = heights.indexOfFirst { it <= current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quality)
            .setSingleChoiceItems(heights.map { "${it}p" }.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                AppSettings.overrideQuality(item.url, heights[which])
                val c = controller ?: return@setSingleChoiceItems
                val position = c.currentPosition
                val playing = c.playWhenReady
                c.setMediaItem(item.toMediaItem(), position)
                c.prepare()
                c.playWhenReady = playing
            }
            .show()
    }

    private fun pickSleepTimer() {
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = minutes.map { if (it == 0) getString(R.string.sleep_off) else getString(R.string.minutes, it) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(labels.toTypedArray()) { _, which ->
                val c = controller ?: return@setItems
                c.sendCustomCommand(
                    SessionCommand(PlaybackService.SLEEP_TIMER, Bundle.EMPTY),
                    bundleOf(PlaybackService.EXTRA_MINUTES to minutes[which]),
                )
                val message = if (minutes[which] == 0) {
                    getString(R.string.sleep_cancelled)
                } else {
                    getString(R.string.sleep_set, minutes[which])
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun renderDescription(description: Description): CharSequence? {
        val content = description.content?.takeIf { it.isNotBlank() } ?: return null
        return if (description.type == Description.HTML) {
            HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_COMPACT)
        } else {
            content
        }
    }

    /** On tablets fullscreen is a choice, not a rotation, since landscape shows details beside the video. */
    private var tabletFullscreen = false
    private var fullscreenNow = false

    private fun setFullscreen(fullscreen: Boolean) {
        if (isTablet) {
            tabletFullscreen = fullscreen
            applyOrientation(resources.configuration)
            return
        }
        requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun autoRotateEnabled() =
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    /**
     * Phones: portrait shows details under the video, landscape is fullscreen.
     * Tablets: portrait the same, landscape puts the details beside the video, and
     * fullscreen is toggled with the button.
     */
    private fun applyOrientation(config: Configuration) {
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        val fullscreen = if (isTablet) tabletFullscreen else landscape
        val sideBySide = isTablet && landscape && !fullscreen
        fullscreenNow = fullscreen
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
        binding.playerView.layoutParams = binding.playerView.layoutParams.apply {
            height = if (fullscreen || sideBySide) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        // Leaving fullscreen goes back to showing the whole picture.
        if (!fullscreen) binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

        binding.root.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.playerContainer.layoutParams = (binding.playerContainer.layoutParams as LinearLayout.LayoutParams).apply {
            if (sideBySide) {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0.62f
            } else {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
                weight = 0f
            }
        }
        binding.details.layoutParams = (binding.details.layoutParams as LinearLayout.LayoutParams).apply {
            if (sideBySide) {
                width = 0
                height = ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0.38f
            } else {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = 0
                weight = 1f
            }
        }
        binding.details.isVisible = !fullscreen && !isInPictureInPictureMode
        binding.playerView.setFullscreenButtonState(fullscreen)
        exitFullscreenOnBack.isEnabled = fullscreen

        val detailsWidth = if (sideBySide) (config.screenWidthDp * 0.38f).toInt() else config.screenWidthDp
        binding.details.useGrid(minItemWidthDp = 380, availableWidthDp = detailsWidth) { it == 0 }

        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
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

    /** Title, stats, actions, channel and description above the related videos. */
    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.Holder>() {
        inner class Holder(val b: PlayerHeaderBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(PlayerHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val b = holder.b
            val item = shown ?: return
            b.title.text = item.title.ifBlank { getString(R.string.loading) }
            b.meta.text = videoMeta(item, includeUploader = false)
            b.meta.isVisible = b.meta.text.isNotEmpty()

            val favorite = Library.isFavorite(item.url)
            b.favorite.setIconResource(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            b.favorite.setOnClickListener {
                val added = Library.toggleFavorite(shown ?: return@setOnClickListener)
                notifyItemChanged(0)
                Toast.makeText(
                    this@PlayerActivity,
                    if (added) R.string.favorite_added else R.string.favorite_removed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            b.share.setOnClickListener { shareVideo(item) }
            b.watchLaterButton.setOnClickListener {
                VideoActions.toggleLater(this@PlayerActivity, Library.WATCH_LATER, shown ?: item)
            }
            b.quality.setOnClickListener { pickQuality() }
            b.audioOnly.isChecked = AppSettings.audioOnly
            b.audioOnly.setOnClickListener {
                AppSettings.audioOnly = !AppSettings.audioOnly
                b.audioOnly.isChecked = AppSettings.audioOnly
                if (AppSettings.audioOnly) Toast.makeText(this@PlayerActivity, R.string.audio_only_on, Toast.LENGTH_SHORT).show()
            }
            b.queueButton.setOnClickListener { showQueue() }
            b.downloadButton.setOnClickListener { DownloadDialog.show(this@PlayerActivity, item) }
            b.commentsLabel.text = commentsLabel ?: getString(R.string.comments)
            b.commentsCard.setOnClickListener { CommentsSheet.show(this@PlayerActivity, item.url) }

            b.chaptersTitle.isVisible = chapters.isNotEmpty()
            b.chaptersScroll.isVisible = chapters.isNotEmpty()
            b.chapters.removeAllViews()
            for (chapter in chapters) {
                val chip = com.google.android.material.chip.Chip(this@PlayerActivity).apply {
                    text = "${formatDuration(chapter.startTimeSeconds.toLong()).ifEmpty { "0:00" }}  ${chapter.title}"
                    setOnClickListener {
                        controller?.seekTo(chapter.startTimeSeconds * 1000L)
                        controller?.play()
                    }
                }
                b.chapters.addView(chip)
            }
            b.sleepTimer.setOnClickListener { pickSleepTimer() }
            b.votes.isVisible = votes != null
            b.votes.text = votes
            b.fullscreen.setOnClickListener { setFullscreen(true) }

            val channelUrl = item.uploaderUrl
            b.channelRow.isVisible = item.uploader.isNotBlank()
            b.channelName.text = item.uploader
            b.channelSubscribers.isVisible = subscribers >= 0
            b.channelSubscribers.text = getString(R.string.subscribers, formatCount(subscribers))
            b.channelAvatar.load(item.uploaderAvatar) {
                transformations(CircleCropTransformation())
                placeholder(R.drawable.avatar_placeholder)
                error(R.drawable.avatar_placeholder)
            }
            b.subscribe.isVisible = channelUrl != null
            if (channelUrl != null) {
                bindSubscribeButton(b.subscribe, Channel(channelUrl, item.uploader, item.uploaderAvatar))
                b.channelRow.setOnClickListener { openChannelOf(item) }
            }

            b.description.isVisible = description != null
            b.description.text = description
            b.description.maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3
            b.description.setOnClickListener {
                descriptionExpanded = !descriptionExpanded
                notifyItemChanged(0)
            }
        }
    }
}
