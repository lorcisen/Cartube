package com.cartube.youtube

import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Recently resolved stream info, shared by the UI and the playback service so a video is
 * only looked up once. Stored under both the requested and the canonical URL, since a
 * shared youtu.be link resolves to a youtube.com/watch URL.
 */
object StreamCache {
    /** Stream URLs stop working after a few hours; refetch well before that. */
    private const val MAX_AGE_MS = 60 * 60 * 1000L
    private const val CAPACITY = 40

    private class Entry(val info: StreamInfo, val time: Long)

    private val map = object : LinkedHashMap<String, Entry>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CAPACITY
    }

    @Synchronized
    fun put(url: String, info: StreamInfo) {
        val entry = Entry(info, System.currentTimeMillis())
        map[url] = entry
        map[info.url] = entry
    }

    @Synchronized
    fun getFresh(url: String): StreamInfo? =
        map[url]?.takeIf { System.currentTimeMillis() - it.time < MAX_AGE_MS }?.info

    /** Any cached info, however old. The player uses it to build sources it already resolved. */
    @Synchronized
    fun get(url: String): StreamInfo? = map[url]?.info
}
