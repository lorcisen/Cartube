package com.cartube.data

import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The subscription feed, kept in memory so the phone and the car share one fetch. */
object Feed {
    private const val MAX_AGE_MS = 15 * 60 * 1000L

    private val mutex = Mutex()
    private var items: List<VideoItem> = emptyList()
    private var lastResult: YouTube.FeedResult? = null
    private var fetchedAt = 0L

    val cached: List<VideoItem> get() = items

    fun invalidate() {
        fetchedAt = 0
    }

    suspend fun load(force: Boolean = false): YouTube.FeedResult = mutex.withLock {
        val stale = System.currentTimeMillis() - fetchedAt > MAX_AGE_MS
        val previous = lastResult
        if (!force && !stale && previous != null && items.isNotEmpty()) return previous

        val result = YouTube.feed(Library.subscriptions())
        lastResult = result
        items = result.videos
        // Only keep a result that found something; otherwise retry next time.
        fetchedAt = if (result.videos.isNotEmpty()) System.currentTimeMillis() else 0
        result
    }
}
