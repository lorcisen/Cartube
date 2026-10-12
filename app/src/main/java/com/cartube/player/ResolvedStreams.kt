package com.cartube.player

import org.schabi.newpipe.extractor.stream.StreamInfo

/** Recently resolved stream info, keyed by video URL. */
class ResolvedStreams(private val capacity: Int = 30) {
    private val map = object : LinkedHashMap<String, StreamInfo>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamInfo>) =
            size > capacity
    }

    @Synchronized
    operator fun get(url: String): StreamInfo? = map[url]

    @Synchronized
    operator fun set(url: String, info: StreamInfo) {
        map[url] = info
    }
}
