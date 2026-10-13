package com.cartube.data

import android.content.Context
import android.content.SharedPreferences
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * A home page built on the device from what you watch, since YouTube's own
 * recommendations need a signed-in account. Recently watched and favourite videos are
 * used as seeds; their "related videos" are pooled and scored.
 *
 * Category chips use each recommended video's *own* YouTube category. Search results
 * don't include it, so it is looked up per video in [refine] and remembered on disk.
 */
object Recommendations {

    class Category(val key: String, val title: String, val videos: List<VideoItem>)

    class Result(
        /** Everything, best first. */
        val all: List<VideoItem>,
        /** Per-category rows; only categories with enough videos. */
        val categories: List<Category>,
        /** True while some videos' categories are still unknown. */
        val categoriesPending: Boolean,
    )

    private const val MAX_SEEDS = 12
    private const val MAX_AGE_MS = 30 * 60 * 1000L
    private const val CATEGORIZE_TOP = 24
    private const val MIN_PER_CATEGORY = 3

    private val mutex = Mutex()
    private var cached: Result? = null
    private var builtAt = 0L
    private var builtFromHistory: String? = null

    val current: Result? get() = cached

    fun init(context: Context) = CategoryCache.init(context)

    /** Quick result: ranking plus whatever categories are already known. */
    suspend fun load(force: Boolean = false): Result = mutex.withLock {
        val history = Library.history()
        val historyKey = history.take(MAX_SEEDS).joinToString { it.url }
        val fresh = System.currentTimeMillis() - builtAt < MAX_AGE_MS && historyKey == builtFromHistory
        cached?.takeIf { fresh && !force }?.let { return it }

        val all = rank(history)
        val result = group(all)
        cached = result
        builtAt = System.currentTimeMillis()
        builtFromHistory = historyKey
        result
    }

    /** Looks up the category of the top videos that don't have one yet, then regroups. */
    suspend fun refine(): Result {
        val base = cached ?: return load()
        if (!base.categoriesPending || com.cartube.net.RateLimit.active) return base
        val missing = base.all.take(CATEGORIZE_TOP).filter { CategoryCache[it.url] == null }
        val limit = Semaphore(2)
        coroutineScope {
            missing.map { video ->
                async(Dispatchers.IO) {
                    limit.withPermit {
                        if (com.cartube.net.RateLimit.active) return@withPermit
                        runCatching { YouTube.streamInfo(video.url) }.getOrNull()?.let {
                            CategoryCache[video.url] = it.category.orEmpty()
                        }
                    }
                }
            }.awaitAll()
        }
        CategoryCache.save()
        return mutex.withLock {
            // Another load may have replaced the list meanwhile; regroup whatever is current.
            group(cached?.all ?: base.all).copyPending(false).also { cached = it }
        }
    }

    private fun Result.copyPending(pending: Boolean) = Result(all, categories, pending)

    private class Candidate(val video: VideoItem) {
        var score = 0.0
    }

    private suspend fun rank(history: List<VideoItem>): List<VideoItem> = coroutineScope {
        // Recent history counts most; a few favourites keep long-term interests alive.
        // Without any history, the newest subscription uploads stand in as seeds.
        val seeds = buildList {
            history.take(8).forEachIndexed { i, v -> add(v to 1.0 / (1 + i * 0.25)) }
            Library.favorites().take(4).forEach { v -> add(v to 0.6) }
            if (isEmpty()) Feed.cached.take(6).forEach { v -> add(v to 0.5) }
        }.distinctBy { it.first.url }.take(MAX_SEEDS)

        val watched = history.map { it.url }.toSet()
        val subscribed = Library.subscriptions().map { it.url }.toSet()
        val knownChannels = history.mapNotNull { it.uploaderUrl }.toSet()

        val limit = Semaphore(3)
        val pools = seeds.map { (seed, weight) ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    runCatching {
                        val info = YouTube.streamInfo(seed.url)
                        CategoryCache[seed.url] = info.category.orEmpty()
                        weight to YouTube.related(info)
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()

        val candidates = linkedMapOf<String, Candidate>()
        for ((weight, related) in pools) {
            related.forEachIndexed { rank, video ->
                if (video.url in watched) return@forEachIndexed
                val c = candidates.getOrPut(video.url) { Candidate(video) }
                // YouTube lists the closest matches first.
                c.score += weight / (1 + rank * 0.15)
            }
        }
        val disliked = Library.notInterestedChannels()
        val hidden = Library.hiddenFilter()
        for (c in candidates.values) {
            if (c.video.uploaderUrl in subscribed) c.score *= 1.3
            else if (c.video.uploaderUrl in knownChannels) c.score *= 1.15
            c.video.uploaderUrl?.let { disliked[it] }?.let { times -> c.score *= Math.pow(0.4, times.toDouble()) }
        }
        spreadChannels(candidates.values.filterNot { hidden(it.video) }.sortedByDescending { it.score }.map { it.video })
    }

    private fun group(all: List<VideoItem>): Result {
        val categories = all
            .mapNotNull { video -> CategoryCache[video.url]?.takeIf { it.isNotBlank() }?.let { it to video } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= MIN_PER_CATEGORY }
            .entries
            .sortedByDescending { it.value.size }
            .map { (key, videos) -> Category(key, categoryTitle(key), videos) }
        val pending = all.take(CATEGORIZE_TOP).any { CategoryCache[it.url] == null }
        return Result(all, categories, pending)
    }

    /** Avoids long runs of the same channel, like YouTube's home page does. */
    private fun spreadChannels(videos: List<VideoItem>): List<VideoItem> {
        val remaining = videos.toMutableList()
        val out = ArrayList<VideoItem>(videos.size)
        while (remaining.isNotEmpty()) {
            val recent = out.takeLast(3).map { it.uploaderUrl ?: it.uploader }.toSet()
            val next = remaining.firstOrNull { (it.uploaderUrl ?: it.uploader) !in recent } ?: remaining.first()
            remaining.remove(next)
            out += next
        }
        return out
    }

    /** YouTube reports categories in English; show them in Swedish. */
    private fun categoryTitle(key: String) = when (key) {
        "Film & Animation" -> "Film och animering"
        "Autos & Vehicles" -> "Bilar och fordon"
        "Music" -> "Musik"
        "Pets & Animals" -> "Djur"
        "Sports" -> "Sport"
        "Travel & Events" -> "Resor och evenemang"
        "Gaming" -> "Spel"
        "People & Blogs" -> "Människor och bloggar"
        "Comedy" -> "Komedi"
        "Entertainment" -> "Underhållning"
        "News & Politics" -> "Nyheter och politik"
        "Howto & Style" -> "Gör det själv och stil"
        "Education" -> "Utbildning"
        "Science & Technology" -> "Vetenskap och teknik"
        "Nonprofits & Activism" -> "Ideellt och engagemang"
        else -> key
    }

    /** Video URL to YouTube category, kept between app starts. */
    private object CategoryCache {
        private const val MAX_ENTRIES = 3000
        private lateinit var prefs: SharedPreferences
        private val map = LinkedHashMap<String, String>()

        fun init(context: Context) {
            prefs = context.getSharedPreferences("categories", Context.MODE_PRIVATE)
            val json = JSONObject(prefs.getString("map", "{}")!!)
            json.keys().forEach { map[it] = json.getString(it) }
        }

        @Synchronized
        operator fun get(url: String): String? = map[url]

        @Synchronized
        operator fun set(url: String, category: String) {
            map.remove(url)
            map[url] = category
            while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
        }

        @Synchronized
        fun save() {
            prefs.edit().putString("map", JSONObject(map as Map<*, *>).toString()).apply()
        }
    }
}
