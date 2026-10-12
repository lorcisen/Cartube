package com.cartube.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.InputStream

object YouTube {
    private val service get() = ServiceList.YouTube

    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        searchInfo(query, YoutubeSearchQueryHandlerFactory.VIDEOS)
            .filterIsInstance<StreamInfoItem>()
            .filterNot { it.isShortFormContent }
            .map { it.toVideoItem() }
    }

    suspend fun searchChannels(query: String): List<Channel> = withContext(Dispatchers.IO) {
        searchInfo(query, YoutubeSearchQueryHandlerFactory.CHANNELS)
            .filterIsInstance<ChannelInfoItem>()
            .map { Channel(it.url, it.name, VideoItem.bestImage(it.thumbnails, 240)) }
    }

    private fun searchInfo(query: String, filter: String): List<InfoItem> {
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
        return SearchInfo.getInfo(service, handler).relatedItems
    }

    /** Stream info for a video, reusing a recent result when there is one. */
    suspend fun streamInfo(url: String, fresh: Boolean = false): StreamInfo {
        if (!fresh) StreamCache.getFresh(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            StreamInfo.getInfo(service, url).also { StreamCache.put(url, it) }
        }
    }

    fun related(info: StreamInfo): List<VideoItem> = info.relatedItems
        .filterIsInstance<StreamInfoItem>()
        .filterNot { it.isShortFormContent }
        .map { it.toVideoItem() }

    class ChannelPage(
        val channel: Channel,
        val subscribers: Long,
        val banner: String?,
        val videos: List<VideoItem>,
    )

    suspend fun channel(url: String): ChannelPage = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(service, url)
        val channel = Channel(info.url, info.name, VideoItem.bestImage(info.avatars, 240))
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val videos = videosTab?.let { tab ->
            ChannelTabInfo.getInfo(service, tab).relatedItems
                .filterIsInstance<StreamInfoItem>()
                .map { it.toVideoItem().copy(uploaderAvatar = channel.avatar) }
        }.orEmpty()
        ChannelPage(channel, info.subscriberCount, VideoItem.bestImage(info.banners, 1280), videos)
    }

    /**
     * Latest uploads from the given channels, newest first. Uses each channel's RSS feed,
     * which is quick and does not need an account.
     */
    suspend fun feed(channels: List<Channel>): List<VideoItem> = coroutineScope {
        val limit = Semaphore(8)
        channels.map { channel ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    runCatching {
                        FeedInfo.getInfo(service, channel.url).relatedItems
                            .filterIsInstance<StreamInfoItem>()
                            .filterNot { it.isShortFormContent }
                            .map { it.toVideoItem().copy(uploaderAvatar = channel.avatar) }
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll()
            .flatten()
            .distinctBy { it.url }
            .sortedByDescending { it.uploadedAt }
            .take(300)
    }

    /** Reads a Google Takeout export (zip, csv or json) of YouTube subscriptions. */
    suspend fun importSubscriptions(input: InputStream, type: String): List<Channel> =
        withContext(Dispatchers.IO) {
            service.subscriptionExtractor.fromInputStream(input, type)
                .map { Channel(it.url, it.name) }
        }

    fun StreamInfoItem.toVideoItem() = VideoItem(
        url = url,
        title = name,
        uploader = uploaderName.orEmpty(),
        thumbnail = VideoItem.bestImage(thumbnails),
        durationSec = duration,
        uploadedAt = uploadDate?.instant?.toEpochMilli() ?: 0,
        views = viewCount,
        uploaderUrl = uploaderUrl,
        uploaderAvatar = VideoItem.bestImage(uploaderAvatars, 240),
    )

    fun StreamInfo.toVideoItem() = VideoItem(
        url = url,
        title = name,
        uploader = uploaderName.orEmpty(),
        thumbnail = VideoItem.bestImage(thumbnails),
        durationSec = duration,
        uploadedAt = uploadDate?.instant?.toEpochMilli() ?: 0,
        views = viewCount,
        uploaderUrl = uploaderUrl,
        uploaderAvatar = VideoItem.bestImage(uploaderAvatars, 240),
    )

    private val linkPattern = Regex(
        """https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/\S+""",
        RegexOption.IGNORE_CASE,
    )

    /** Finds a YouTube link in shared text such as "Check this out: https://youtu.be/...". */
    fun findLink(text: String?): String? = text?.let { linkPattern.find(it)?.value }
}
