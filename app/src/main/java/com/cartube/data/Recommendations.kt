package com.cartube.data

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

/**
 * A home page built on the device from what you watch, since YouTube's own
 * recommendations need a signed-in account. Recently watched and favourite videos are
 * used as seeds; their "related videos" are pooled, scored and grouped by the seed's
 * YouTube category (Music, Sports, Education ...).
 */
object Recommendations {

    class Category(val key: String, val title: String, val videos: List<VideoItem>)

    class Result(
        /** Everything, best first. */
        val all: List<VideoItem>,
        /** Per-category rows; only categories with enough videos. */
        val categories: List<Category>,
    )

    private const val MAX_SEEDS = 12
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    private val mutex = Mutex()
    private var cached: Result? = null
    private var builtAt = 0L
    private var builtFromHistory: String? = null

    val current: Result? get() = cached

    suspend fun load(force: Boolean = false): Result = mutex.withLock {
        val history = Library.history()
        val historyKey = history.take(MAX_SEEDS).joinToString { it.url }
        val fresh = System.currentTimeMillis() - builtAt < MAX_AGE_MS && historyKey == builtFromHistory
        cached?.takeIf { fresh && !force }?.let { return it }

        val result = build(history)
        cached = result
        builtAt = System.currentTimeMillis()
        builtFromHistory = historyKey
        result
    }

    private class Candidate(val video: VideoItem) {
        var score = 0.0
        val categories = mutableMapOf<String, Double>()
    }

    private suspend fun build(history: List<VideoItem>): Result = coroutineScope {
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

        val limit = Semaphore(4)
        val pools = seeds.map { (seed, weight) ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    runCatching {
                        val info = YouTube.streamInfo(seed.url)
                        Triple(info.category.orEmpty(), weight, YouTube.related(info))
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()

        val candidates = linkedMapOf<String, Candidate>()
        for ((category, weight, related) in pools) {
            related.forEachIndexed { rank, video ->
                if (video.url in watched) return@forEachIndexed
                val c = candidates.getOrPut(video.url) { Candidate(video) }
                // YouTube lists the closest matches first.
                val points = weight / (1 + rank * 0.15)
                c.score += points
                if (category.isNotBlank()) c.categories.merge(category, points, Double::plus)
            }
        }
        for (c in candidates.values) {
            if (c.video.uploaderUrl in subscribed) c.score *= 1.3
            else if (c.video.uploaderUrl in knownChannels) c.score *= 1.15
        }

        val ranked = candidates.values.sortedByDescending { it.score }
        val all = spreadChannels(ranked.map { it.video })

        val categories = ranked
            .mapNotNull { c -> c.categories.maxByOrNull { it.value }?.key?.let { it to c.video } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 4 }
            .entries
            .sortedByDescending { it.value.size }
            .map { (key, videos) -> Category(key, categoryTitle(key), spreadChannels(videos)) }

        Result(all, categories)
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
}
