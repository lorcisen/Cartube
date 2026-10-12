package com.cartube.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.cartube.net.Http
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Turns a [MediaItem] whose mediaId is a YouTube URL into a playable source. The stream
 * info must already be in [streams]; the session callback resolves it before items are
 * handed to the player, because this factory is called on the main thread.
 */
class YoutubeMediaSourceFactory(
    context: Context,
    private val streams: ResolvedStreams,
) : MediaSource.Factory {

    private val fallback = DefaultMediaSourceFactory(context)
    private val dashData = YoutubeDataSource.Factory(rangeAsParameter = true)
    private val progressiveData = YoutubeDataSource.Factory(rangeAsParameter = false)
    private val liveData = DefaultHttpDataSource.Factory().setUserAgent(Http.USER_AGENT)

    private var drmProvider: DrmSessionManagerProvider? = null
    private var errorPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply {
        drmProvider = provider
        fallback.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply {
        errorPolicy = policy
        fallback.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = fallback.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val info = streams[mediaItem.mediaId]
            ?: return fallback.createMediaSource(mediaItem)
        return try {
            build(info, mediaItem)
        } catch (e: Exception) {
            // Let the player report the failure instead of crashing the service.
            Log.e(TAG, "Could not build source for ${mediaItem.mediaId}", e)
            fallback.createMediaSource(mediaItem)
        }
    }

    private fun build(info: StreamInfo, item: MediaItem): MediaSource {
        val live = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live && !info.hlsUrl.isNullOrEmpty()) {
            return HlsMediaSource.Factory(liveData)
                .configure()
                .createMediaSource(item.buildUpon().setUri(info.hlsUrl).build())
        }

        val audio = pickAudio(info.audioStreams)?.let { youtubeSource(it, info, item) }
        val video = pickVideo(info.videoOnlyStreams)?.let { youtubeSource(it, info, item) }
        if (audio != null && video != null) return MergingMediaSource(true, video, audio)

        // Older uploads sometimes only have streams with sound and picture muxed together.
        val muxed = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height }
        if (muxed != null) return progressive(muxed.content, item)

        return audio ?: video ?: throw IllegalStateException("No playable stream for ${info.url}")
    }

    /** Builds a DASH source from a manifest generated locally for a single YouTube stream. */
    private fun youtubeSource(stream: Stream, info: StreamInfo, item: MediaItem): MediaSource {
        val itag = stream.itagItem ?: return progressive(stream.content, item)
        return try {
            val manifest = when (stream.deliveryMethod) {
                DeliveryMethod.DASH ->
                    YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, itag, info.duration)
                else ->
                    YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                        stream.content, itag, info.duration,
                    )
            }
            val parsed = DashManifestParser().parse(Uri.parse(stream.content), manifest.byteInputStream())
            DashMediaSource.Factory(DefaultDashChunkSource.Factory(dashData), dashData)
                .configure()
                .createMediaSource(parsed, item)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create DASH manifest, falling back to progressive", e)
            progressive(stream.content, item)
        }
    }

    private fun progressive(url: String, item: MediaItem): MediaSource =
        ProgressiveMediaSource.Factory(progressiveData)
            .configure()
            .createMediaSource(item.buildUpon().setUri(url).build())

    private fun <F : MediaSource.Factory> F.configure(): F = apply {
        drmProvider?.let { setDrmSessionManagerProvider(it) }
        errorPolicy?.let { setLoadErrorHandlingPolicy(it) }
    }

    private fun usable(stream: Stream) = stream.isUrl &&
        (stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP ||
            (stream.deliveryMethod == DeliveryMethod.DASH && stream.itagItem != null))

    private fun pickVideo(streams: List<VideoStream>): VideoStream? = streams
        .filter { usable(it) && it.height in 1..MAX_HEIGHT }
        .sortedWith(
            compareByDescending<VideoStream> { it.height }
                .thenByDescending { it.fps }
                .thenByDescending { if (it.format == MediaFormat.MPEG_4) 1 else 0 },
        )
        .firstOrNull()

    private fun pickAudio(streams: List<AudioStream>): AudioStream? = streams
        .filter { usable(it) }
        .sortedWith(
            compareByDescending<AudioStream> {
                it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
            }
                .thenByDescending { if (it.format == MediaFormat.M4A) 1 else 0 }
                .thenByDescending { it.averageBitrate },
        )
        .firstOrNull()

    private companion object {
        const val TAG = "YoutubeSourceFactory"
        const val MAX_HEIGHT = 1080
    }
}
