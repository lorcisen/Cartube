package com.cartube.data

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.cartube.player.ChunkedDataSource
import com.cartube.player.DownloadServiceImpl
import com.cartube.player.YoutubeDataSource
import com.cartube.youtube.VideoItem
import com.cartube.youtube.videoIdOf
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.util.concurrent.Executors

/**
 * Offline videos. Each download is one or two files (video-only and audio, or just audio)
 * stored in an ExoPlayer cache, and played back by merging them, so nothing needs to be
 * remuxed on the phone.
 */
object Downloads {

    /** A downloaded (or downloading) video. */
    class Entry(val video: VideoItem, val percent: Int, val done: Boolean, val failed: Boolean, val audioOnly: Boolean)

    private lateinit var appContext: Context
    private val listeners = mutableSetOf<() -> Unit>()

    private val cache: SimpleCache by lazy {
        SimpleCache(File(appContext.filesDir, "offline"), NoOpCacheEvictor(), database)
    }
    private val database by lazy { StandaloneDatabaseProvider(appContext) }

    val manager: DownloadManager by lazy {
        val upstream = ChunkedDataSource.Factory(YoutubeDataSource.Factory(rangeAsParameter = true))
        DownloadManager(appContext, database, cache, upstream, Executors.newFixedThreadPool(2)).apply {
            maxParallelDownloads = 2
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) = notifyChanged()
                override fun onDownloadRemoved(manager: DownloadManager, download: Download) = notifyChanged()
            })
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }
    fun removeListener(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }
    private fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    /** Heights offered in the download dialog, highest first. */
    fun heights(info: StreamInfo): List<Int> = info.videoOnlyStreams
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..1080 }
        .map { it.height }.distinct().sortedDescending()

    /** Starts downloading [info] at [height], or audio only when [height] is null. */
    fun start(context: Context, info: StreamInfo, video: VideoItem, height: Int?) {
        val id = videoIdOf(info.url) ?: error("No video id")
        val audio = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate })
            .firstOrNull() ?: error("No downloadable audio")
        val data = video.toJson().put("audioOnly", height == null).toString().toByteArray()
        send(context, DownloadRequest.Builder("$id:audio", android.net.Uri.parse(audio.content)).setCustomCacheKey("$id:audio").setData(data).build())
        if (height != null) {
            val stream = info.videoOnlyStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height == height }
                .sortedByDescending { if (it.format == MediaFormat.MPEG_4) 1 else 0 }
                .firstOrNull() ?: error("No downloadable video")
            send(context, DownloadRequest.Builder("$id:video", android.net.Uri.parse(stream.content)).setCustomCacheKey("$id:video").setData(data).build())
        }
    }

    private fun send(context: Context, request: DownloadRequest) {
        manager // make sure it exists before the service asks for it
        DownloadService.sendAddDownload(context, DownloadServiceImpl::class.java, request, false)
    }

    fun delete(context: Context, url: String) {
        val id = videoIdOf(url) ?: return
        for (part in listOf("audio", "video")) {
            DownloadService.sendRemoveDownload(context, DownloadServiceImpl::class.java, "$id:$part", false)
        }
    }

    fun entries(): List<Entry> {
        val byVideo = LinkedHashMap<String, MutableList<Download>>()
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val d = cursor.download
                byVideo.getOrPut(d.request.id.substringBefore(':')) { mutableListOf() } += d
            }
        }
        return byVideo.values.mapNotNull { parts ->
            val json = runCatching { JSONObject(String(parts.first().request.data)) }.getOrNull() ?: return@mapNotNull null
            val audioOnly = json.optBoolean("audioOnly")
            val expected = if (audioOnly) 1 else 2
            val done = parts.size >= expected && parts.all { it.state == Download.STATE_COMPLETED }
            val failed = parts.any { it.state == Download.STATE_FAILED }
            val percent = parts.map { if (it.state == Download.STATE_COMPLETED) 100f else it.percentDownloaded.coerceAtLeast(0f) }
                .average().toInt()
            Entry(VideoItem.fromJson(json), percent, done, failed, audioOnly)
        }.sortedByDescending { it.done }
    }

    fun isDownloaded(url: String): Boolean = find(url)?.done == true

    fun find(url: String): Entry? {
        val id = videoIdOf(url) ?: return null
        return entries().firstOrNull { videoIdOf(it.video.url) == id }
    }

    /** A source that plays the downloaded files, or null if the video isn't fully downloaded. */
    fun localSource(item: MediaItem): MediaSource? {
        val entry = find(item.mediaId)?.takeIf { it.done } ?: return null
        val id = videoIdOf(entry.video.url) ?: return null
        val factory = CacheDataSource.Factory().setCache(cache)
        fun part(name: String): MediaSource {
            val request = manager.downloadIndex.getDownload("$id:$name")!!.request
            val media = item.buildUpon().setUri(request.uri).setCustomCacheKey("$id:$name").build()
            return ProgressiveMediaSource.Factory(factory).createMediaSource(media)
        }
        return if (entry.audioOnly) part("audio") else MergingMediaSource(part("video"), part("audio"))
    }
}
