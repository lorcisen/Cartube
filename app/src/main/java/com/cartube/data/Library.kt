package com.cartube.data

import android.content.Context
import android.content.SharedPreferences
import com.cartube.youtube.Channel
import com.cartube.youtube.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch history, favourites and subscriptions, kept in SharedPreferences.
 *
 * Every entry carries the time it was added (`t`), and removals are remembered as
 * tombstones, so two devices can be merged by [Sync] without removed items coming back.
 */
object Library {
    private const val HISTORY = "history"
    private const val FAVORITES = "favorites"
    private const val SUBSCRIPTIONS = "subscriptions"
    private const val REMOVED_FAVORITES = "removed_favorites"
    private const val REMOVED_SUBSCRIPTIONS = "removed_subscriptions"
    private const val HISTORY_CLEARED_AT = "history_cleared_at"
    private const val MAX_HISTORY = 100
    private const val MAX_TOMBSTONES = 500

    private lateinit var prefs: SharedPreferences
    private val subscriptionListeners = mutableSetOf<() -> Unit>()
    private val changeListeners = mutableSetOf<() -> Unit>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    }

    /** Called on the main thread whenever subscriptions change. */
    fun addSubscriptionListener(listener: () -> Unit) = synchronized(subscriptionListeners) { subscriptionListeners += listener }
    fun removeSubscriptionListener(listener: () -> Unit) = synchronized(subscriptionListeners) { subscriptionListeners -= listener }

    /** Called after any local change; [Sync] uses it to schedule an upload. */
    fun addChangeListener(listener: () -> Unit) = synchronized(changeListeners) { changeListeners += listener }

    @Synchronized
    fun history(): List<VideoItem> = readArray(HISTORY).map(VideoItem::fromJson)

    @Synchronized
    fun favorites(): List<VideoItem> = readArray(FAVORITES).map(VideoItem::fromJson)

    fun addToHistory(item: VideoItem) {
        synchronized(this) {
            val entry = item.toJson().put("t", now())
            val items = listOf(entry) + readArray(HISTORY).filter { it.getString("url") != item.url }
            writeArray(HISTORY, items.take(MAX_HISTORY))
        }
        notifyChanged()
    }

    fun clearHistory() {
        synchronized(this) {
            writeArray(HISTORY, emptyList())
            prefs.edit().putLong(HISTORY_CLEARED_AT, now()).apply()
        }
        notifyChanged()
    }

    @Synchronized
    fun isFavorite(url: String) = readArray(FAVORITES).any { it.getString("url") == url }

    /** Returns true if the item is a favourite after the call. */
    fun toggleFavorite(item: VideoItem): Boolean {
        val added = synchronized(this) { toggle(FAVORITES, REMOVED_FAVORITES, item.url, item.toJson()) }
        notifyChanged()
        return added
    }

    @Synchronized
    fun find(url: String): VideoItem? =
        (readArray(FAVORITES) + readArray(HISTORY)).firstOrNull { it.getString("url") == url }
            ?.let(VideoItem::fromJson)

    @Synchronized
    fun subscriptions(): List<Channel> =
        readArray(SUBSCRIPTIONS).map(Channel::fromJson).sortedBy { it.name.lowercase() }

    @Synchronized
    fun isSubscribed(url: String) = readArray(SUBSCRIPTIONS).any { it.getString("url") == url }

    /** Returns true if subscribed after the call. */
    fun toggleSubscription(channel: Channel): Boolean {
        val subscribed = synchronized(this) {
            toggle(SUBSCRIPTIONS, REMOVED_SUBSCRIPTIONS, channel.url, channel.toJson())
        }
        notifySubscriptions()
        notifyChanged()
        return subscribed
    }

    /** Adds channels not already subscribed to; returns how many were new. */
    fun addSubscriptions(channels: List<Channel>): Int {
        val added = synchronized(this) {
            val current = readArray(SUBSCRIPTIONS)
            val known = current.map { it.getString("url") }.toSet()
            val fresh = channels.filter { it.url !in known }.distinctBy { it.url }
            val t = now()
            writeArray(SUBSCRIPTIONS, current + fresh.map { it.toJson().put("t", t) })
            removeTombstones(REMOVED_SUBSCRIPTIONS, fresh.map { it.url })
            fresh.size
        }
        if (added > 0) {
            notifySubscriptions()
            notifyChanged()
        }
        return added
    }

    /** Stores a channel's avatar once we have seen it, so lists can show it. */
    @Synchronized
    fun updateChannel(channel: Channel) {
        val current = readArray(SUBSCRIPTIONS)
        val existing = current.firstOrNull { it.getString("url") == channel.url } ?: return
        if (Channel.fromJson(existing) == channel) return
        writeArray(
            SUBSCRIPTIONS,
            current.map { if (it === existing) channel.toJson().put("t", existing.optLong("t")) else it },
        )
    }

    // region Sync

    /** Everything that is synced between devices, as one JSON document. */
    @Synchronized
    fun export(): JSONObject = JSONObject()
        .put("version", 1)
        .put(HISTORY, JSONArray(readArray(HISTORY)))
        .put(FAVORITES, JSONArray(readArray(FAVORITES)))
        .put(SUBSCRIPTIONS, JSONArray(readArray(SUBSCRIPTIONS)))
        .put(REMOVED_FAVORITES, readTombstones(REMOVED_FAVORITES))
        .put(REMOVED_SUBSCRIPTIONS, readTombstones(REMOVED_SUBSCRIPTIONS))
        .put(HISTORY_CLEARED_AT, prefs.getLong(HISTORY_CLEARED_AT, 0))

    /** Merges another device's [export] into this one. The newest change to each entry wins. */
    fun merge(remote: JSONObject) {
        val subscriptionsChanged = synchronized(this) {
            val before = readArray(SUBSCRIPTIONS).map { it.getString("url") }.toSet()

            val clearedAt = maxOf(prefs.getLong(HISTORY_CLEARED_AT, 0), remote.optLong(HISTORY_CLEARED_AT))
            val history = (readArray(HISTORY) + remote.optArray(HISTORY))
                .groupBy { it.getString("url") }
                .map { (_, entries) -> entries.maxBy { it.optLong("t") } }
                .filter { it.optLong("t") > clearedAt }
                .sortedByDescending { it.optLong("t") }
                .take(MAX_HISTORY)
            writeArray(HISTORY, history)
            prefs.edit().putLong(HISTORY_CLEARED_AT, clearedAt).apply()

            mergeSet(FAVORITES, REMOVED_FAVORITES, remote)
            mergeSet(SUBSCRIPTIONS, REMOVED_SUBSCRIPTIONS, remote)

            readArray(SUBSCRIPTIONS).map { it.getString("url") }.toSet() != before
        }
        if (subscriptionsChanged) notifySubscriptions()
    }

    private fun mergeSet(key: String, removedKey: String, remote: JSONObject) {
        val removed = readTombstones(removedKey).toMap()
        val remoteRemoved = remote.optJSONObject(removedKey)?.toMap().orEmpty()
        val allRemoved = (removed.keys + remoteRemoved.keys).associateWith {
            maxOf(removed[it] ?: 0, remoteRemoved[it] ?: 0)
        }
        val merged = (readArray(key) + remote.optArray(key))
            .groupBy { it.getString("url") }
            .map { (_, entries) -> entries.maxBy { it.optLong("t") } }
            .filter { it.optLong("t") > (allRemoved[it.getString("url")] ?: 0) }
            .sortedByDescending { it.optLong("t") }
        writeArray(key, merged)
        writeTombstones(removedKey, allRemoved)
    }

    // endregion

    /** Returns true if the url is in the list after the call. */
    private fun toggle(key: String, removedKey: String, url: String, json: JSONObject): Boolean {
        val current = readArray(key)
        val exists = current.any { it.getString("url") == url }
        if (exists) {
            writeArray(key, current.filter { it.getString("url") != url })
            writeTombstones(removedKey, readTombstones(removedKey).toMap() + (url to now()))
        } else {
            writeArray(key, listOf(json.put("t", now())) + current)
            removeTombstones(removedKey, listOf(url))
        }
        return !exists
    }

    private fun removeTombstones(key: String, urls: List<String>) {
        if (urls.isEmpty()) return
        writeTombstones(key, readTombstones(key).toMap() - urls.toSet())
    }

    private fun notifySubscriptions() {
        synchronized(subscriptionListeners) { subscriptionListeners.toList() }.forEach { it() }
    }

    private fun notifyChanged() {
        synchronized(changeListeners) { changeListeners.toList() }.forEach { it() }
    }

    private fun now() = System.currentTimeMillis()

    private fun readArray(key: String): List<JSONObject> =
        JSONArray(prefs.getString(key, "[]")).objects()

    private fun writeArray(key: String, items: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }

    private fun readTombstones(key: String): JSONObject = JSONObject(prefs.getString(key, "{}")!!)

    private fun writeTombstones(key: String, tombstones: Map<String, Long>) {
        val newest = tombstones.entries.sortedByDescending { it.value }.take(MAX_TOMBSTONES)
        val json = JSONObject()
        newest.forEach { json.put(it.key, it.value) }
        prefs.edit().putString(key, json.toString()).apply()
    }

    private fun JSONObject.toMap(): Map<String, Long> = keys().asSequence().associateWith { optLong(it) }

    private fun JSONObject.optArray(key: String): List<JSONObject> = optJSONArray(key)?.objects().orEmpty()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
