package com.cartube.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** Like and dislike estimates from the Return YouTube Dislike project. */
object Dislikes {
    class Votes(val likes: Long, val dislikes: Long)

    suspend fun votes(videoId: String): Votes? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId")
                .header("User-Agent", "CarTube")
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                Votes(json.optLong("likes", -1), json.optLong("dislikes", -1))
            }
        }.getOrNull()
    }
}
