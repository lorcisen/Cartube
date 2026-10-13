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
    private const val PLAYLISTS = "playlists"
    private const val REMOVED_PLAYLISTS = "removed_playlists"
    private const val BLOCKED = "blocked_channels"
    private const val REMOVED_BLOCKED = "removed_blocked_channels"
    private const val NOT_INTERESTED = "not_interested"
    const val WATCH_LATER = "watch_later"
    const val LISTEN_LATER = "listen_later"
    private const val REMOVED_WATCH_LATER = "removed_watch_later"
    private const val REMOVED_LISTEN_LATER = "removed_listen_later"
    private const val REMOVED_NOT_INTERESTED = "removed_not_interested"
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

    // region Watch later / listen later

    private fun removedKey(list: String) = if (list == WATCH_LATER) REMOVED_WATCH_LATER else REMOVED_LISTEN_LATER

    /** [WATCH_LATER] or [LISTEN_LATER], oldest first so they play in the order saved. */
    @Synchronized
    fun later(list: String): List<VideoItem> = readArray(list).map(VideoItem::fromJson).reversed()

    @Synchronized
    fun isLater(list: String, url: String) = readArray(list).any { it.getString("url") == url }

    /** Returns true if the video is in the list after the call. */
    fun toggleLater(list: String, item: VideoItem): Boolean {
        val added = synchronized(this) { toggle(list, removedKey(list), item.url, item.toJson()) }
        notifyChanged()
        return added
    }

    /** Called when a video has been watched to the end. */
    fun finished(url: String) {
        var changed = false
        synchronized(this) {
            for (list in listOf(WATCH_LATER, LISTEN_LATER)) {
                if (readArray(list).any { it.getString("url") == url }) {
                    toggle(list, removedKey(list), url, JSONObject())
                    changed = true
                }
            }
        }
        if (changed) notifyChanged()
    }

    // endregion

    // region Playlists

    /** A user playlist. Saved YouTube playlists have [sourceUrl] and are loaded on demand. */
    data class Playlist(val id: String, val name: String, val videos: List<VideoItem>, val sourceUrl: String? = null)

    @Synchronized
    fun playlists(): List<Playlist> = readArray(PLAYLISTS).map { json ->
        Playlist(
            id = json.getString("url"),
            name = json.optString("name"),
            videos = json.optJSONArray("videos")?.objects()?.map(VideoItem::fromJson).orEmpty(),
            sourceUrl = json.optString("source").takeIf { it.isNotBlank() },
        )
    }

    @Synchronized
    fun playlist(id: String): Playlist? = playlists().firstOrNull { it.id == id }

    fun createPlaylist(name: String, first: VideoItem? = null): Playlist {
        val playlist = Playlist("local:" + java.util.UUID.randomUUID(), name, listOfNotNull(first))
        savePlaylist(playlist)
        return playlist
    }

    /** Saves a YouTube playlist to the library; its videos are fetched when opened. */
    fun saveYoutubePlaylist(url: String, name: String) {
        savePlaylist(Playlist(url, name, emptyList(), sourceUrl = url))
    }

    fun addToPlaylist(id: String, item: VideoItem) {
        val playlist = playlist(id) ?: return
        if (playlist.videos.any { it.url == item.url }) return
        savePlaylist(playlist.copy(videos = playlist.videos + item))
    }

    fun removeFromPlaylist(id: String, url: String) {
        val playlist = playlist(id) ?: return
        savePlaylist(playlist.copy(videos = playlist.videos.filter { it.url != url }))
    }

    fun deletePlaylist(id: String) {
        synchronized(this) {
            writeArray(PLAYLISTS, readArray(PLAYLISTS).filter { it.getString("url") != id })
            writeTombstones(REMOVED_PLAYLISTS, readTombstones(REMOVED_PLAYLISTS).toMap() + (id to now()))
        }
        notifyChanged()
    }

    private fun savePlaylist(playlist: Playlist) {
        synchronized(this) {
            val json = JSONObject()
                .put("url", playlist.id)
                .put("name", playlist.name)
                .put("source", playlist.sourceUrl ?: "")
                .put("videos", JSONArray(playlist.videos.map { it.toJson() }))
                .put("t", now())
            val rest = readArray(PLAYLISTS).filter { it.getString("url") != playlist.id }
            writeArray(PLAYLISTS, listOf(json) + rest)
            removeTombstones(REMOVED_PLAYLISTS, listOf(playlist.id))
        }
        notifyChanged()
    }

    // endregion

    // region Blocking

    @Synchronized
    fun blockedChannels(): List<Channel> = readArray(BLOCKED).map(Channel::fromJson)

    fun blockChannel(channel: Channel) {
        synchronized(this) {
            if (readArray(BLOCKED).none { it.getString("url") == channel.url }) {
                toggle(BLOCKED, REMOVED_BLOCKED, channel.url, channel.toJson())
            }
        }
        notifyChanged()
    }

    fun unblockChannel(url: String) {
        synchronized(this) {
            if (readArray(BLOCKED).any { it.getString("url") == url }) {
                toggle(BLOCKED, REMOVED_BLOCKED, url, JSONObject())
            }
        }
        notifyChanged()
    }

    /** Hides a video and makes its channel less likely to be recommended. */
    fun markNotInterested(item: VideoItem) {
        synchronized(this) {
            val json = JSONObject().put("url", item.url).put("channel", item.uploaderUrl ?: "")
            if (readArray(NOT_INTERESTED).none { it.getString("url") == item.url }) {
                toggle(NOT_INTERESTED, REMOVED_NOT_INTERESTED, item.url, json)
            }
        }
        notifyChanged()
    }

    @Synchronized
    fun notInterestedChannels(): Map<String, Int> = readArray(NOT_INTERESTED)
        .mapNotNull { it.optString("channel").takeIf { c -> c.isNotBlank() } }
        .groupingBy { it }.eachCount()

    /** True for videos that should not be shown: blocked channel or marked not interested. */
    fun isHidden(item: VideoItem): Boolean = hiddenFilter()(item)

    /** A fast predicate for filtering long lists. */
    @Synchronized
    fun hiddenFilter(): (VideoItem) -> Boolean {
        val blocked = readArray(BLOCKED).map { it.getString("url") }.toSet()
        val notInterested = readArray(NOT_INTERESTED).map { it.getString("url") }.toSet()
        return { it.url in notInterested || (it.uploaderUrl != null && it.uploaderUrl in blocked) }
    }

    // endregion

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
        .put(PLAYLISTS, JSONArray(readArray(PLAYLISTS)))
        .put(REMOVED_PLAYLISTS, readTombstones(REMOVED_PLAYLISTS))
        .put(BLOCKED, JSONArray(readArray(BLOCKED)))
        .put(REMOVED_BLOCKED, readTombstones(REMOVED_BLOCKED))
        .put(NOT_INTERESTED, JSONArray(readArray(NOT_INTERESTED)))
        .put(WATCH_LATER, JSONArray(readArray(WATCH_LATER)))
        .put(REMOVED_WATCH_LATER, readTombstones(REMOVED_WATCH_LATER))
        .put(LISTEN_LATER, JSONArray(readArray(LISTEN_LATER)))
        .put(REMOVED_LISTEN_LATER, readTombstones(REMOVED_LISTEN_LATER))
        .put(REMOVED_NOT_INTERESTED, readTombstones(REMOVED_NOT_INTERESTED))

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
            mergeSet(PLAYLISTS, REMOVED_PLAYLISTS, remote)
            mergeSet(BLOCKED, REMOVED_BLOCKED, remote)
            mergeSet(NOT_INTERESTED, REMOVED_NOT_INTERESTED, remote)
            mergeSet(WATCH_LATER, REMOVED_WATCH_LATER, remote)
            mergeSet(LISTEN_LATER, REMOVED_LISTEN_LATER, remote)

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
