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

    private var prefs: android.content.SharedPreferences? = null
    private var appContext: android.content.Context? = null

    /** Loads the last fetched feed from disk, so the home screen and widget have something at once. */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("feed", android.content.Context.MODE_PRIVATE)
        items = saved()
    }

    fun saved(): List<VideoItem> {
        val array = org.json.JSONArray(prefs?.getString("items", "[]") ?: "[]")
        return (0 until array.length()).map { VideoItem.fromJson(array.getJSONObject(it)) }
    }

    private fun persist(videos: List<VideoItem>) {
        prefs?.edit()?.putString("items", org.json.JSONArray(videos.take(30).map { it.toJson() }).toString())?.apply()
        appContext?.let { com.cartube.widget.LatestWidget.refresh(it) }
    }

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
        if (result.videos.isNotEmpty()) persist(result.videos)
        // Only keep a result that found something; otherwise retry next time.
        fetchedAt = if (result.videos.isNotEmpty()) System.currentTimeMillis() else 0
        result
    }
}
