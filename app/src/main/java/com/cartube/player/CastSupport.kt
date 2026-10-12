package com.cartube.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.cartube.youtube.StreamCache
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/** Registered in the manifest; tells the Cast SDK to use Google's default media receiver. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        // Media3 already shows the notification and media session.
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setNotificationOptions(null)
                .setMediaSessionEnabled(false)
                .build(),
        )
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * A Chromecast fetches the video itself and can only play a single URL, so it gets the
 * best stream that has picture and sound together (or the HLS stream of a live video).
 * Returns null when the video has no such stream.
 */
fun castableItem(item: MediaItem): MediaItem? {
    val info = StreamCache.get(item.mediaId) ?: return null
    val muxed = info.videoStreams
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        .maxByOrNull { it.height }
    val (uri, mime) = when {
        muxed != null -> muxed.content to MimeTypes.VIDEO_MP4
        !info.hlsUrl.isNullOrEmpty() -> info.hlsUrl to MimeTypes.APPLICATION_M3U8
        else -> return null
    }
    return item.buildUpon().setUri(uri).setMimeType(mime).build()
}
