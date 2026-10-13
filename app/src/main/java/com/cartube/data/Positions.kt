package com.cartube.data

import android.content.Context
import android.content.SharedPreferences
import com.cartube.youtube.VideoItem
import org.json.JSONObject

/** Where each video was left off, so playback can resume there. */
object Positions {
    private const val MAX = 500
    private const val MIN_RESUME_MS = 10_000L
    private const val END_MARGIN_MS = 15_000L

    private lateinit var prefs: SharedPreferences
    private val map = LinkedHashMap<String, LongArray>() // url -> [position, duration, time]

    fun init(context: Context) {
        prefs = context.getSharedPreferences("positions", Context.MODE_PRIVATE)
        val json = JSONObject(prefs.getString("map", "{}")!!)
        json.keys().forEach { url ->
            val entry = json.getJSONArray(url)
            map[url] = longArrayOf(entry.getLong(0), entry.getLong(1), entry.optLong(2))
        }
    }

    @Synchronized
    fun save(url: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        map.remove(url)
        if (positionMs >= durationMs - END_MARGIN_MS) {
            // Finished: remember it as watched to the end.
            map[url] = longArrayOf(durationMs, durationMs, System.currentTimeMillis())
        } else {
            map[url] = longArrayOf(positionMs, durationMs, System.currentTimeMillis())
        }
        while (map.size > MAX) map.remove(map.keys.first())
        persist()
    }

    /** Where to start, or 0 to start from the beginning. */
    @Synchronized
    fun resumeAt(url: String): Long {
        val (position, duration) = map[url]?.let { it[0] to it[1] } ?: return 0
        return if (position in MIN_RESUME_MS until duration - END_MARGIN_MS) position else 0
    }

    /** Watched fraction 0..1, or null if never played. */
    @Synchronized
    fun progress(url: String): Float? = map[url]?.let { (it[0].toFloat() / it[1]).coerceIn(0f, 1f) }

    /** Videos started but not finished, most recent first. */
    @Synchronized
    fun unfinished(history: List<VideoItem>): List<VideoItem> {
        val recent = map.entries
            .filter { (_, v) -> v[0] in MIN_RESUME_MS until v[1] - END_MARGIN_MS }
            .sortedByDescending { it.value[2] }
            .map { it.key }
        val byUrl = history.associateBy { it.url }
        return recent.mapNotNull { byUrl[it] }
    }

    private fun persist() {
        val json = JSONObject()
        map.forEach { (url, v) -> json.put(url, org.json.JSONArray().put(v[0]).put(v[1]).put(v[2])) }
        prefs.edit().putString("map", json.toString()).apply()
    }
}
