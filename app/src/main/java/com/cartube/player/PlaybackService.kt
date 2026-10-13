package com.cartube.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Bundle
import com.cartube.R
import com.cartube.data.Feed
import com.cartube.data.Library
import com.cartube.data.Recommendations
import com.cartube.data.Settings
import com.cartube.ui.PlayerActivity
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import com.cartube.youtube.YouTube.toVideoItem
import com.google.android.gms.cast.framework.CastContext
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the player. The phone UI and Android Auto both connect to it as media controllers;
 * Android Auto additionally browses the feed, favourites and history and searches through it.
 * When a Chromecast session starts, playback moves from the local player to the cast player.
 */
class PlaybackService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val searchResults = ConcurrentHashMap<String, List<VideoItem>>()

    private lateinit var exoPlayer: ExoPlayer
    private var castPlayer: CastPlayer? = null
    private var session: MediaLibrarySession? = null

    private lateinit var carConnection: LiveData<Int>
    private val carObserver = Observer<Int> { type ->
        setVideoEnabled(type != CarConnection.CONNECTION_TYPE_PROJECTION)
    }

    private val isCasting get() = castPlayer != null && session?.player === castPlayer

    private lateinit var skipper: SegmentSkipper
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleep = Runnable { session?.player?.pause() }
    private var autoplayJob: kotlinx.coroutines.Job? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                session?.player?.setPlaybackSpeed(Settings.defaultSpeed)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) playNextRelated()
        }
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(YoutubeMediaSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, exoPlayer, LibraryCallback())
            .setSessionActivity(openPlayer)
            .build()

        // While the phone is projecting to a car, only the audio is played: no video
        // is decoded or shown anywhere, and the car shows its normal media controls.
        carConnection = CarConnection(this).type
        carConnection.observeForever(carObserver)

        skipper = SegmentSkipper(this, scope)
        skipper.attach(exoPlayer)
        exoPlayer.addListener(playerListener)

        castPlayer = runCatching { CastPlayer(CastContext.getSharedInstance(this)) }
            .onFailure { Log.w(TAG, "Cast is not available", it) }
            .getOrNull()
            ?.apply {
                setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() = switchTo(this@apply)
                    override fun onCastSessionUnavailable() = switchTo(exoPlayer)
                })
                if (isCastSessionAvailable) switchTo(this)
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player ?: return stopSelf()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        carConnection.removeObserver(carObserver)
        handler.removeCallbacks(sleep)
        skipper.release()
        scope.cancel()
        castPlayer?.run {
            setSessionAvailabilityListener(null)
            release()
        }
        session?.run {
            exoPlayer.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun setVideoEnabled(enabled: Boolean) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /** Moves the queue and position from the current player to [target]. */
    private fun switchTo(target: Player) {
        val session = session ?: return
        val from = session.player
        if (from === target) return

        val toCast = target === castPlayer
        val items = (0 until from.mediaItemCount)
            .map { from.getMediaItemAt(it) }
            .mapNotNull { if (toCast) castableItem(it) else it.buildUpon().setMimeType(null).build() }
        val index = from.currentMediaItemIndex.coerceIn(0, maxOf(items.size - 1, 0))
        val position = if (from.playbackState == Player.STATE_IDLE) 0 else from.currentPosition
        val playWhenReady = from.playWhenReady

        from.stop()
        from.clearMediaItems()
        if (items.isNotEmpty()) {
            target.setMediaItems(items, index, position)
            target.playWhenReady = playWhenReady
            target.prepare()
        }
        session.player = target
        from.removeListener(playerListener)
        target.addListener(playerListener)
        skipper.attach(target)
    }

    /** With autoplay on, queues the top related video the user has not seen when one ends. */
    private fun playNextRelated() {
        val player = session?.player ?: return
        if (!Settings.autoplay || player.hasNextMediaItem() || autoplayJob?.isActive == true) return
        val current = player.currentMediaItem?.mediaId ?: return
        autoplayJob = scope.launch {
            val info = runCatching { YouTube.streamInfo(current) }.getOrNull() ?: return@launch
            val watched = Library.history().map { it.url }.toSet()
            val next = YouTube.related(info).firstOrNull { it.url !in watched } ?: return@launch
            val item = resolve(next.toMediaItem()) ?: return@launch
            val p = session?.player ?: return@launch
            if (p.playbackState != Player.STATE_ENDED) return@launch
            p.addMediaItem(item)
            p.seekToNextMediaItem()
            p.prepare()
            p.play()
        }
    }

    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleep)
        if (minutes > 0) handler.postDelayed(sleep, minutes * 60_000L)
    }

    /** Fetches stream URLs for an item; also handles voice requests ("play X"). */
    private suspend fun resolve(item: MediaItem): MediaItem? {
        val url = item.mediaId.ifBlank {
            val query = item.requestMetadata.searchQuery ?: return null
            YouTube.search(query).firstOrNull()?.url ?: return null
        }
        return try {
            val info = YouTube.streamInfo(url)
            val video = info.toVideoItem()
            Library.addToHistory(video)
            val resolved = video.toMediaItem()
            if (isCasting) castableItem(resolved) else resolved
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve $url", e)
            null
        }
    }

    private suspend fun search(query: String): List<VideoItem> =
        searchResults[query] ?: YouTube.search(query).also { searchResults[query] = it }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_VIDEOS)
                .build(),
        )
        .build()

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return this
        return drop(page * pageSize).take(pageSize)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(SLEEP_TIMER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == SLEEP_TIMER) {
                setSleepTimer(args.getInt(EXTRA_MINUTES))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, getString(R.string.app_name)), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = when (parentId) {
                ROOT -> listOf(
                    folder(FOR_YOU, getString(R.string.nav_for_you)),
                    folder(FEED, getString(R.string.nav_home)),
                    folder(FAVORITES, getString(R.string.tab_favorites)),
                    folder(HISTORY, getString(R.string.tab_history)),
                )
                FOR_YOU -> runCatching { Recommendations.load().all }
                    .getOrElse { Recommendations.current?.all.orEmpty() }
                    .take(50).map { it.toMediaItem() }
                FEED -> runCatching { Feed.load().videos }.getOrDefault(Feed.cached).map { it.toMediaItem() }
                FAVORITES -> Library.favorites().map { it.toMediaItem() }
                HISTORY -> Library.history().map { it.toMediaItem() }
                else -> return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(children.page(page, pageSize)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = (
                Library.find(mediaId)
                    ?: Feed.cached.firstOrNull { it.url == mediaId }
                    ?: Recommendations.current?.all?.firstOrNull { it.url == mediaId }
                )
                ?.toMediaItem()
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val count = runCatching { search(query).size }.getOrDefault(0)
                session.notifySearchResultChanged(browser, query, count, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = search(query).page(page, pageSize).map { it.toMediaItem() }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            val resolved = mediaItems.mapNotNull { resolve(it) }
            if (resolved.isEmpty() && mediaItems.isNotEmpty()) {
                throw IllegalStateException("None of the requested videos could be loaded")
            }
            resolved.toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val last = Library.history().firstOrNull()
                ?: throw UnsupportedOperationException("Nothing to resume")
            val item = resolve(last.toMediaItem())
                ?: throw IllegalStateException("Could not resume ${last.url}")
            MediaItemsWithStartPosition(listOf(item), 0, C.TIME_UNSET)
        }
    }

    companion object {
        const val SLEEP_TIMER = "com.cartube.SLEEP_TIMER"
        const val EXTRA_MINUTES = "minutes"
        private const val TAG = "PlaybackService"
        private const val ROOT = "root"
        private const val FOR_YOU = "for_you"
        private const val FEED = "feed"
        private const val FAVORITES = "favorites"
        private const val HISTORY = "history"
    }
}
