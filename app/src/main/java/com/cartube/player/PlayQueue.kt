package com.cartube.player

import com.cartube.youtube.VideoItem

/**
 * Videos waiting to be played after the current one ("Spela härnäst", "Lägg i kö",
 * playlists). Only the next video is resolved and handed to the player ahead of time, so
 * a long playlist does not mean fetching every video's streams up front.
 *
 * Lives in the app process and is shared by the UI and [PlaybackService].
 */
object PlayQueue {
    private val items = ArrayList<VideoItem>()
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized
    fun upcoming(): List<VideoItem> = items.toList()

    fun playNext(item: VideoItem) = edit { items.removeAll { it.url == item.url }; items.add(0, item) }

    fun add(item: VideoItem) = edit { items.removeAll { it.url == item.url }; items.add(item) }

    /** Replaces the queue, e.g. with the rest of a playlist. */
    fun replace(newItems: List<VideoItem>) = edit { items.clear(); items.addAll(newItems) }

    fun clear() = edit { items.clear() }

    fun remove(url: String) = edit { items.removeAll { it.url == url } }

    /** Called by the service when the player moved on to [url]. */
    fun consumed(url: String) = edit { items.removeAll { it.url == url } }

    @Synchronized
    fun peek(): VideoItem? = items.firstOrNull()

    fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }
    fun removeListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }

    private fun edit(block: () -> Unit) {
        synchronized(this) { block() }
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }
}
