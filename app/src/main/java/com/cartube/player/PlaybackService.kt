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
import com.cartube.data.Downloads
import com.cartube.data.Feed
import com.cartube.data.Library
import com.cartube.data.Recommendations
import com.cartube.data.Positions
import com.cartube.data.Settings
import com.cartube.data.WatchStats
import com.cartube.notify.Reminders
import com.cartube.ui.PlayerActivity
import com.cartube.youtube.StreamCache
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
    private var inCar = false
    private val carObserver = Observer<Int> { type ->
        inCar = type == CarConnection.CONNECTION_TYPE_PROJECTION
        updateVideoEnabled()
    }

    private val audioEffects = AudioEffects()
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Settings.KEY_AUDIO_ONLY -> updateVideoEnabled()
            Settings.KEY_NORMALIZE, Settings.KEY_CLEAR_SPEECH -> audioEffects.refresh()
        }
    }
    private val queueListener: () -> Unit = { handler.post { prefetchNext() } }
    private var prefetchJob: kotlinx.coroutines.Job? = null

    /** Saves the playback position every few seconds so videos resume where they stopped. */
    private val savePosition = object : Runnable {
        override fun run() {
            rememberPosition()
            countWatchTime()
            handler.postDelayed(this, 10_000)
        }
    }

    private var lastTick = 0L

    /** Adds the time since the last tick to today's watch time while playing. */
    private fun countWatchTime() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastTick > 0) {
            val channel = session?.player?.currentMediaItem?.mediaMetadata?.artist?.toString()
            WatchStats.add(now - lastTick, channel)
            if (WatchStats.shouldRemind(Settings.dailyLimitMinutes)) Reminders.watchTime(this, WatchStats.todayMs())
        }
        lastTick = now
    }

    private val isCasting get() = castPlayer != null && session?.player === castPlayer

    private lateinit var skipper: SegmentSkipper
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleep = Runnable { session?.player?.pause() }
    private var autoplayJob: kotlinx.coroutines.Job? = null

    /**
     * Set when a requested video could not be loaded. The play() that follows the request
     * then finds an empty player, which Media3 treats as "resume the last video"; that must
     * not start whatever was watched before.
     */
    private var refuseResumptionUntil = 0L

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                session?.player?.setPlaybackSpeed(Settings.defaultSpeed)
            }
            applySubtitlePreference()
            mediaItem?.let { PlayQueue.consumed(it.mediaId) }
            prefetchNext()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // Leaving a video: remember where it was left.
            val old = oldPosition.mediaItem ?: return
            if (old.mediaId != newPosition.mediaItem?.mediaId) {
                // The player already reports the new video's duration, so use the old one's info.
                val duration = StreamCache.get(old.mediaId)?.duration?.times(1000) ?: return
                savePosition(old.mediaId, oldPosition.positionMs, duration)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(savePosition)
            if (isPlaying) {
                lastTick = android.os.SystemClock.elapsedRealtime()
                handler.postDelayed(savePosition, 10_000)
            } else {
                countWatchTime()
                lastTick = 0
                rememberPosition()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = audioEffects.apply(audioSessionId)

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                session?.player?.currentMediaItem?.let { Library.finished(it.mediaId) }
                playNextRelated()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            retryAfterError()
        }
    }

    /**
     * Retries a failed video once with freshly fetched stream URLs and without subtitles,
     * from where it stopped. Stream URLs expire and caption requests can be refused, and
     * either is reported as a generic source error.
     */
    private fun retryAfterError() {
        val player = session?.player ?: return
        if (player !== exoPlayer) return
        val item = player.currentMediaItem ?: return
        val url = item.mediaId
        if (!PlaybackRecovery.markRetry(url)) return
        val position = player.currentPosition
        scope.launch {
            runCatching { YouTube.streamInfo(url, fresh = true) }.getOrNull() ?: return@launch
            if (exoPlayer.currentMediaItem?.mediaId != url) return@launch
            // Insert-and-remove (not replace) so ExoPlayer builds a new source from the fresh info.
            val index = exoPlayer.currentMediaItemIndex
            exoPlayer.addMediaItem(index + 1, item)
            exoPlayer.removeMediaItem(index)
            exoPlayer.seekTo(index, position)
            exoPlayer.prepare()
            exoPlayer.play()
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

        applySubtitlePreference()
        skipper = SegmentSkipper(this, scope)
        skipper.attach(exoPlayer)
        exoPlayer.addListener(playerListener)
        Settings.addListener(settingsListener)
        PlayQueue.addListener(queueListener)
        updateVideoEnabled()
        audioEffects.apply(exoPlayer.audioSessionId)

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
        handler.removeCallbacks(savePosition)
        rememberPosition()
        Settings.removeListener(settingsListener)
        PlayQueue.removeListener(queueListener)
        audioEffects.release()
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

    /** The chosen subtitle track carries the default flag (see Subtitles); "off" shows none. */
    private fun applySubtitlePreference() {
        val off = Settings.subtitles == Settings.SUBTITLES_OFF
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(if (Settings.subtitles == Settings.SUBTITLES_SWEDISH) "sv" else null)
            .setIgnoredTextSelectionFlags(if (off) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }

    private fun rememberPosition() {
        val player = session?.player ?: return
        val item = player.currentMediaItem ?: return
        if (player.duration > 0) savePosition(item.mediaId, player.currentPosition, player.duration)
    }

    /** Saves the position; a video watched to the end leaves Watch later / Listen later. */
    private fun savePosition(url: String, position: Long, duration: Long) {
        Positions.save(url, position, duration)
        if (duration > 0 && position >= duration - 15_000) Library.finished(url)
    }

    /**
     * Hands the first queued video to the player as the next item, resolving its streams
     * ahead of time. Anything after the current item that no longer matches the queue is
     * dropped first, so "Spela härnäst" takes effect immediately.
     */
    private fun prefetchNext() {
        val player = session?.player ?: return
        val next = PlayQueue.peek()
        val index = player.currentMediaItemIndex
        val following = if (index + 1 < player.mediaItemCount) player.getMediaItemAt(index + 1) else null
        if (following != null && following.mediaId == next?.url) return
        if (following != null) player.removeMediaItems(index + 1, player.mediaItemCount)
        if (next == null || player.mediaItemCount == 0) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val item = resolve(next.toMediaItem(), addToHistory = false) ?: run {
                PlayQueue.remove(next.url)
                return@launch
            }
            val p = session?.player ?: return@launch
            if (PlayQueue.peek()?.url != next.url) return@launch
            val ended = p.playbackState == Player.STATE_ENDED
            p.addMediaItem(p.currentMediaItemIndex + 1, item)
            if (ended) {
                p.seekToNextMediaItem()
                p.play()
            }
        }
    }

    private fun updateVideoEnabled() = setVideoEnabled(!inCar && !Settings.audioOnly)

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
        if (PlayQueue.peek() != null) return prefetchNext()
        if (!Settings.autoplay || player.hasNextMediaItem() || autoplayJob?.isActive == true) return
        val current = player.currentMediaItem?.mediaId ?: return
        autoplayJob = scope.launch {
            val info = runCatching { YouTube.streamInfo(current) }.getOrNull() ?: return@launch
            val watched = Library.history().map { it.url }.toSet()
            val hidden = Library.hiddenFilter()
            val next = YouTube.related(info).firstOrNull { it.url !in watched && !hidden(it) } ?: return@launch
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
    private suspend fun resolve(item: MediaItem, addToHistory: Boolean = true): MediaItem? {
        val url = item.mediaId.ifBlank {
            val query = item.requestMetadata.searchQuery ?: return null
            YouTube.search(query).firstOrNull()?.url ?: return null
        }
        // Offline: use what was saved with the download instead of asking YouTube.
        runCatching { Downloads.find(url) }.getOrNull()?.takeIf { it.done }?.let { entry ->
            if (addToHistory) Library.addToHistory(entry.video)
            return entry.video.toMediaItem()
        }
        return try {
            val info = YouTube.streamInfo(url)
            val video = info.toVideoItem()
            if (addToHistory) Library.addToHistory(video)
            val resolved = video.toMediaItem()
            if (isCasting) castableItem(resolved) else resolved
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve $url", e)
            null
        }
    }

    private suspend fun search(query: String): List<VideoItem> =
        searchResults[query] ?: YouTube.search(query).also { searchResults[query] = it }

    private fun folder(id: String, title: String, artwork: String? = null) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtworkUri(artwork?.let(android.net.Uri::parse))
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
                ROOT -> listOfNotNull(
                    folder(FOR_YOU, getString(R.string.nav_for_you)),
                    folder(CONTINUE, getString(R.string.continue_watching))
                        .takeIf { Positions.unfinished(Library.history()).isNotEmpty() },
                    folder(LISTEN_LATER, getString(R.string.listen_later))
                        .takeIf { Library.later(Library.LISTEN_LATER).isNotEmpty() },
                    folder(QUEUE, getString(R.string.queue)).takeIf { PlayQueue.upcoming().isNotEmpty() },
                    folder(FEED, getString(R.string.nav_home)),
                    folder(CHANNELS, getString(R.string.tab_subscriptions)),
                    folder(PLAYLISTS, getString(R.string.playlists)),
                    folder(DOWNLOADS, getString(R.string.downloads)),
                    folder(FAVORITES, getString(R.string.tab_favorites)),
                    folder(WATCH_LATER, getString(R.string.watch_later)),
                    folder(HISTORY, getString(R.string.tab_history)),
                )
                LISTEN_LATER -> Library.later(Library.LISTEN_LATER).map { it.toMediaItem() }
                WATCH_LATER -> Library.later(Library.WATCH_LATER).map { it.toMediaItem() }
                CONTINUE -> Positions.unfinished(Library.history()).map { it.toMediaItem() }
                QUEUE -> PlayQueue.upcoming().map { it.toMediaItem() }
                CHANNELS -> Library.subscriptions().map { folder(CHANNEL_PREFIX + it.url, it.name, it.avatar) }
                PLAYLISTS -> Library.playlists().map { folder(PLAYLIST_PREFIX + it.id, it.name, it.videos.firstOrNull()?.thumbnail) }
                DOWNLOADS -> runCatching { Downloads.entries() }.getOrDefault(emptyList())
                    .filter { it.done }.map { it.video.toMediaItem() }
                FOR_YOU -> runCatching { Recommendations.load().all }
                    .getOrElse { Recommendations.current?.all.orEmpty() }
                    .take(50).map { it.toMediaItem() }
                FEED -> runCatching { Feed.load().videos }.getOrDefault(Feed.cached).map { it.toMediaItem() }
                FAVORITES -> Library.favorites().map { it.toMediaItem() }
                HISTORY -> Library.history().map { it.toMediaItem() }
                else -> when {
                    parentId.startsWith(CHANNEL_PREFIX) -> runCatching {
                        YouTube.channel(parentId.removePrefix(CHANNEL_PREFIX)).videos
                    }.getOrDefault(emptyList()).map { it.toMediaItem() }
                    parentId.startsWith(PLAYLIST_PREFIX) -> {
                        val playlist = Library.playlist(parentId.removePrefix(PLAYLIST_PREFIX))
                            ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                        val videos = playlist.sourceUrl?.let { url ->
                            runCatching { YouTube.playlist(url, limit = 100).videos }.getOrDefault(emptyList())
                        } ?: playlist.videos
                        videos.map { it.toMediaItem() }
                    }
                    else -> return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }
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

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val resolved = mediaItems.mapNotNull { resolve(it) }
            if (resolved.isEmpty() && mediaItems.isNotEmpty()) {
                // Don't leave the previous video playing as if it were the one asked for.
                session?.player?.run {
                    stop()
                    clearMediaItems()
                }
                refuseResumptionUntil = android.os.SystemClock.elapsedRealtime() + 10_000
                throw IllegalStateException("None of the requested videos could be loaded")
            }
            val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, maxOf(resolved.size - 1, 0))
            // Continue where the video was left off, unless a position was asked for.
            val position = if (startPositionMs == C.TIME_UNSET) {
                resolved.getOrNull(index)?.let { Positions.resumeAt(it.mediaId) } ?: 0
            } else {
                startPositionMs
            }
            MediaItemsWithStartPosition(resolved, index, position)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            if (android.os.SystemClock.elapsedRealtime() < refuseResumptionUntil) {
                throw UnsupportedOperationException("The requested video could not be played")
            }
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
        private const val CONTINUE = "continue"
        private const val LISTEN_LATER = "listen_later"
        private const val WATCH_LATER = "watch_later"
        private const val QUEUE = "queue"
        private const val CHANNELS = "channels"
        private const val PLAYLISTS = "playlists"
        private const val DOWNLOADS = "downloads"
        private const val CHANNEL_PREFIX = "channel:"
        private const val PLAYLIST_PREFIX = "playlist:"
        private const val HISTORY = "history"
    }
}
