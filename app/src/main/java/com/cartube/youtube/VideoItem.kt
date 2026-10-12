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
    /** Upload time in epoch millis, or 0 when unknown. */
    val uploadedAt: Long = 0,
    /** View count, or -1 when unknown. */
    val views: Long = -1,
    val uploaderUrl: String? = null,
    val uploaderAvatar: String? = null,
) {
    fun toMediaItem(): MediaItem {
        val extras = Bundle().apply { putString(EXTRA_JSON, toJson().toString()) }
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
        .put("uploadedAt", uploadedAt)
        .put("views", views)
        .put("uploaderUrl", uploaderUrl)
        .put("uploaderAvatar", uploaderAvatar)

    fun putInto(intent: Intent): Intent = intent.putExtra(EXTRA_JSON, toJson().toString())

    companion object {
        private const val EXTRA_JSON = "com.cartube.VIDEO"

        fun fromJson(json: JSONObject) = VideoItem(
            url = json.getString("url"),
            title = json.optString("title"),
            uploader = json.optString("uploader"),
            thumbnail = json.optStringOrNull("thumbnail"),
            durationSec = json.optLong("duration"),
            uploadedAt = json.optLong("uploadedAt"),
            views = json.optLong("views", -1),
            uploaderUrl = json.optStringOrNull("uploaderUrl"),
            uploaderAvatar = json.optStringOrNull("uploaderAvatar"),
        )

        fun fromIntent(intent: Intent): VideoItem? =
            intent.getStringExtra(EXTRA_JSON)?.let { fromJson(JSONObject(it)) }

        fun fromMediaItem(item: MediaItem): VideoItem {
            item.mediaMetadata.extras?.getString(EXTRA_JSON)?.let {
                return fromJson(JSONObject(it))
            }
            return VideoItem(
                url = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                uploader = item.mediaMetadata.artist?.toString().orEmpty(),
                thumbnail = item.mediaMetadata.artworkUri?.toString(),
                durationSec = 0,
            )
        }

        /** A placeholder for a bare link; details are filled in once the video loads. */
        fun fromLink(url: String) = VideoItem(url, "", "", null, 0)

        /** Picks an image that looks sharp in a list without being huge. */
        fun bestImage(images: List<Image>, maxWidth: Int = 720): String? =
            images.filter { it.width in 1..maxWidth }.maxByOrNull { it.width }?.url
                ?: images.lastOrNull()?.url

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
