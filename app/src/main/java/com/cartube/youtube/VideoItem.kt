package com.cartube.youtube

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject
import org.schabi.newpipe.extractor.Image

data class VideoItem(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnail: String?,
    val durationSec: Long,
) {
    fun toMediaItem(): MediaItem {
        val extras = Bundle().apply { putLong(EXTRA_DURATION, durationSec) }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(uploader)
            .setArtworkUri(thumbnail?.toUri())
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(url)
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("title", title)
        .put("uploader", uploader)
        .put("thumbnail", thumbnail)
        .put("duration", durationSec)

    fun putInto(intent: Intent): Intent = intent.putExtra(EXTRA_JSON, toJson().toString())

    companion object {
        private const val EXTRA_JSON = "com.cartube.VIDEO"
        private const val EXTRA_DURATION = "com.cartube.DURATION"

        fun fromJson(json: JSONObject) = VideoItem(
            url = json.getString("url"),
            title = json.optString("title"),
            uploader = json.optString("uploader"),
            thumbnail = json.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" },
            durationSec = json.optLong("duration"),
        )

        fun fromIntent(intent: Intent): VideoItem? =
            intent.getStringExtra(EXTRA_JSON)?.let { fromJson(JSONObject(it)) }

        fun fromMediaItem(item: MediaItem) = VideoItem(
            url = item.mediaId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            uploader = item.mediaMetadata.artist?.toString().orEmpty(),
            thumbnail = item.mediaMetadata.artworkUri?.toString(),
            durationSec = item.mediaMetadata.extras?.getLong(EXTRA_DURATION) ?: 0,
        )

        /** Picks a thumbnail that looks sharp in a list without being huge. */
        fun bestThumbnail(images: List<Image>): String? =
            images.filter { it.width in 1..720 }.maxByOrNull { it.width }?.url
                ?: images.lastOrNull()?.url
    }
}
