package com.cartube.data

import android.content.Context
import android.content.SharedPreferences
import com.cartube.youtube.VideoItem
import org.json.JSONArray

/** Watch history and favourites, kept in SharedPreferences. */
object Library {
    private const val HISTORY = "history"
    private const val FAVORITES = "favorites"
    private const val MAX_HISTORY = 100

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun history(): List<VideoItem> = read(HISTORY)

    @Synchronized
    fun favorites(): List<VideoItem> = read(FAVORITES)

    @Synchronized
    fun addToHistory(item: VideoItem) {
        val items = listOf(item) + read(HISTORY).filter { it.url != item.url }
        write(HISTORY, items.take(MAX_HISTORY))
    }

    @Synchronized
    fun isFavorite(url: String) = read(FAVORITES).any { it.url == url }

    /** Returns true if the item is a favourite after the call. */
    @Synchronized
    fun toggleFavorite(item: VideoItem): Boolean {
        val current = read(FAVORITES)
        val exists = current.any { it.url == item.url }
        write(FAVORITES, if (exists) current.filter { it.url != item.url } else listOf(item) + current)
        return !exists
    }

    @Synchronized
    fun find(url: String): VideoItem? =
        read(FAVORITES).firstOrNull { it.url == url } ?: read(HISTORY).firstOrNull { it.url == url }

    private fun read(key: String): List<VideoItem> {
        val array = JSONArray(prefs.getString(key, "[]"))
        return (0 until array.length()).map { VideoItem.fromJson(array.getJSONObject(it)) }
    }

    private fun write(key: String, items: List<VideoItem>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        prefs.edit().putString(key, array.toString()).apply()
    }
}
