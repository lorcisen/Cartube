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
    private var fetchedAt = 0L

    val cached: List<VideoItem> get() = items

    fun invalidate() {
        fetchedAt = 0
    }

    suspend fun load(force: Boolean = false): List<VideoItem> = mutex.withLock {
        val stale = System.currentTimeMillis() - fetchedAt > MAX_AGE_MS
        if (force || stale || items.isEmpty()) {
            items = YouTube.feed(Library.subscriptions())
            fetchedAt = System.currentTimeMillis()
        }
        items
    }
}
