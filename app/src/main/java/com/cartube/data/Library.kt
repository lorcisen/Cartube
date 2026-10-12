package com.cartube.data

import android.content.Context
import android.content.SharedPreferences
import com.cartube.youtube.Channel
import com.cartube.youtube.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/** Watch history, favourites and subscriptions, kept in SharedPreferences. */
object Library {
    private const val HISTORY = "history"
    private const val FAVORITES = "favorites"
    private const val SUBSCRIPTIONS = "subscriptions"
    private const val MAX_HISTORY = 100

    private lateinit var prefs: SharedPreferences
    private val listeners = mutableSetOf<() -> Unit>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    }

    /** Called on the main thread whenever subscriptions change. */
    fun addSubscriptionListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }
    fun removeSubscriptionListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }

    @Synchronized
    fun history(): List<VideoItem> = readVideos(HISTORY)

    @Synchronized
    fun favorites(): List<VideoItem> = readVideos(FAVORITES)

    @Synchronized
    fun addToHistory(item: VideoItem) {
        val items = listOf(item) + readVideos(HISTORY).filter { it.url != item.url }
        writeVideos(HISTORY, items.take(MAX_HISTORY))
    }

    @Synchronized
    fun clearHistory() = writeVideos(HISTORY, emptyList())

    @Synchronized
    fun isFavorite(url: String) = readVideos(FAVORITES).any { it.url == url }

    /** Returns true if the item is a favourite after the call. */
    @Synchronized
    fun toggleFavorite(item: VideoItem): Boolean {
        val current = readVideos(FAVORITES)
        val exists = current.any { it.url == item.url }
        writeVideos(FAVORITES, if (exists) current.filter { it.url != item.url } else listOf(item) + current)
        return !exists
    }

    @Synchronized
    fun find(url: String): VideoItem? =
        readVideos(FAVORITES).firstOrNull { it.url == url } ?: readVideos(HISTORY).firstOrNull { it.url == url }

    @Synchronized
    fun subscriptions(): List<Channel> = readChannels().sortedBy { it.name.lowercase() }

    @Synchronized
    fun isSubscribed(url: String) = readChannels().any { it.url == url }

    /** Returns true if subscribed after the call. */
    fun toggleSubscription(channel: Channel): Boolean {
        val subscribed = synchronized(this) {
            val current = readChannels()
            val exists = current.any { it.url == channel.url }
            writeChannels(if (exists) current.filter { it.url != channel.url } else current + channel)
            !exists
        }
        notifySubscriptions()
        return subscribed
    }

    /** Adds channels not already subscribed to; returns how many were new. */
    fun addSubscriptions(channels: List<Channel>): Int {
        val added = synchronized(this) {
            val current = readChannels()
            val known = current.map { it.url }.toSet()
            val fresh = channels.filter { it.url !in known }.distinctBy { it.url }
            writeChannels(current + fresh)
            fresh.size
        }
        if (added > 0) notifySubscriptions()
        return added
    }

    /** Stores a channel's avatar once we have seen it, so lists can show it. */
    @Synchronized
    fun updateChannel(channel: Channel) {
        val current = readChannels()
        if (current.any { it.url == channel.url && it != channel }) {
            writeChannels(current.map { if (it.url == channel.url) channel else it })
        }
    }

    private fun notifySubscriptions() {
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    private fun readArray(key: String): List<JSONObject> {
        val array = JSONArray(prefs.getString(key, "[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun writeArray(key: String, items: List<JSONObject>) {
        val array = JSONArray()
        items.forEach { array.put(it) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun readVideos(key: String) = readArray(key).map(VideoItem::fromJson)
    private fun writeVideos(key: String, items: List<VideoItem>) = writeArray(key, items.map { it.toJson() })
    private fun readChannels() = readArray(SUBSCRIPTIONS).map(Channel::fromJson)
    private fun writeChannels(items: List<Channel>) = writeArray(SUBSCRIPTIONS, items.map { it.toJson() })
}
