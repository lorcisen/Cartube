package com.cartube.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest

/**
 * Community-submitted segments (sponsor reads, "like and subscribe" reminders, intros...)
 * from the SponsorBlock project, https://sponsor.ajay.app.
 *
 * Uses the privacy-preserving lookup: only the first characters of a hash of the video id
 * are sent, and the matching video is picked out locally.
 */
object SponsorBlock {
    class Segment(val startMs: Long, val endMs: Long, val category: String)

    suspend fun segments(videoId: String, categories: Set<String>): List<Segment> =
        withContext(Dispatchers.IO) {
            if (categories.isEmpty()) return@withContext emptyList()
            val prefix = sha256(videoId).take(4)
            val categoryParam = JSONArray(categories.toList()).toString()
            val url = "https://sponsor.ajay.app/api/skipSegments/$prefix?categories=" +
                java.net.URLEncoder.encode(categoryParam, "UTF-8")
            val request = Request.Builder().url(url).header("User-Agent", "CarTube").build()
            Http.client.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext emptyList()
                if (!response.isSuccessful) return@withContext emptyList()
                val videos = JSONArray(response.body?.string() ?: "[]")
                val video = (0 until videos.length()).map { videos.getJSONObject(it) }
                    .firstOrNull { it.optString("videoID") == videoId }
                    ?: return@withContext emptyList()
                val segments = video.optJSONArray("segments") ?: return@withContext emptyList()
                (0 until segments.length()).mapNotNull { i ->
                    val s = segments.getJSONObject(i)
                    if (s.optString("actionType", "skip") != "skip") return@mapNotNull null
                    val range = s.getJSONArray("segment")
                    Segment(
                        startMs = (range.getDouble(0) * 1000).toLong(),
                        endMs = (range.getDouble(1) * 1000).toLong(),
                        category = s.optString("category"),
                    )
                }.filter { it.endMs > it.startMs }
            }
        }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
