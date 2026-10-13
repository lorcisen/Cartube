package com.cartube.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.InputStream

object YouTube {
    private val service get() = ServiceList.YouTube

    /**
     * Video search. YouTube's own order is relevance; more [pages] give a bigger pool to
     * sort and filter locally (about 20 videos per page).
     */
    suspend fun search(query: String, pages: Int = 1): List<VideoItem> = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val first = SearchInfo.getInfo(service, handler)
        val items = first.relatedItems.toMutableList()
        var next = first.nextPage
        var fetched = 1
        while (fetched < pages && next != null) {
            val more = runCatching { SearchInfo.getMoreItems(service, handler, next) }.getOrNull() ?: break
            items += more.items
            next = more.nextPage
            fetched++
        }
        items.filterIsInstance<StreamInfoItem>()
            .filterNot { it.isShortFormContent }
            .map { it.toVideoItem() }
            .distinctBy { it.url }
    }

    suspend fun searchChannels(query: String): List<Channel> = withContext(Dispatchers.IO) {
        searchInfo(query, YoutubeSearchQueryHandlerFactory.CHANNELS)
            .filterIsInstance<ChannelInfoItem>()
            .map { Channel(it.url, it.name, VideoItem.bestImage(it.thumbnails, 240)) }
    }

    class PlaylistSummary(val url: String, val name: String, val uploader: String, val thumbnail: String?, val count: Long)

    suspend fun searchPlaylists(query: String): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        searchInfo(query, YoutubeSearchQueryHandlerFactory.PLAYLISTS)
            .filterIsInstance<PlaylistInfoItem>()
            .map { PlaylistSummary(it.url, it.name, it.uploaderName.orEmpty(), VideoItem.bestImage(it.thumbnails), it.streamCount) }
    }

    class PlaylistPage(val name: String, val uploader: String, val thumbnail: String?, val videos: List<VideoItem>)

    /** A YouTube playlist with up to [limit] videos (following YouTube's pages). */
    suspend fun playlist(url: String, limit: Int = 300): PlaylistPage = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(service, url)
        val videos = info.relatedItems.toMutableList()
        var page = info.nextPage
        while (page != null && videos.size < limit) {
            val more = PlaylistInfo.getMoreItems(service, url, page)
            videos += more.items
            page = more.nextPage
        }
        PlaylistPage(
            name = info.name,
            uploader = info.uploaderName.orEmpty(),
            thumbnail = VideoItem.bestImage(info.thumbnails),
            videos = videos.map { it.toVideoItem() },
        )
    }

    class Comment(
        val author: String,
        val avatar: String?,
        val text: String,
        val likes: Int,
        val age: String,
        val replies: Int,
        val pinned: Boolean,
        val hearted: Boolean,
    )

    class CommentsPage(val comments: List<Comment>, val total: Int, val disabled: Boolean, val next: Page?)

    suspend fun comments(url: String, page: Page? = null): CommentsPage = withContext(Dispatchers.IO) {
        if (page == null) {
            val info = CommentsInfo.getInfo(service, url)
            CommentsPage(info.relatedItems.map { it.toComment() }, info.commentsCount, info.isCommentsDisabled, info.nextPage)
        } else {
            val more = CommentsInfo.getMoreItems(service, url, page)
            CommentsPage(more.items.map { it.toComment() }, -1, false, more.nextPage)
        }
    }

    private fun CommentsInfoItem.toComment() = Comment(
        author = uploaderName.orEmpty(),
        avatar = VideoItem.bestImage(uploaderAvatars, 120),
        text = commentText?.content.orEmpty(),
        likes = likeCount,
        age = textualUploadDate.orEmpty(),
        replies = replyCount,
        pinned = isPinned,
        hearted = isHeartedByUploader,
    )

    /**
     * One of YouTube's trending lists ("Trending", "trending_music", "trending_gaming",
     * "trending_movies_and_shows", "trending_podcasts_episodes", "live") for a country code.
     */
    suspend fun trending(kioskId: String, country: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val direct = runCatching { kioskVideos(kioskId, country) }
        val videos = direct.getOrNull().orEmpty()
        if (videos.isNotEmpty() || kioskId != GENERAL_TRENDING) return@withContext direct.getOrThrow()

        // YouTube retired its general trending page; build "general" from the category
        // lists instead, taking turns so no single category dominates.
        val lists = GENERAL_PARTS.map { id -> runCatching { kioskVideos(id, country) }.getOrDefault(emptyList()) }
        if (lists.all { it.isEmpty() }) direct.getOrThrow()
        val mixed = ArrayList<VideoItem>()
        for (i in 0 until lists.maxOf { it.size }) lists.forEach { list -> list.getOrNull(i)?.let { mixed += it } }
        mixed.distinctBy { it.url }
    }

    /** Reads a kiosk's first page directly (its title parsing is what breaks most often). */
    private fun kioskVideos(kioskId: String, country: String): List<VideoItem> {
        val extractor = service.kioskList.getExtractorById(kioskId, null)
        extractor.forceContentCountry(ContentCountry(country))
        extractor.fetchPage()
        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filterNot { it.isShortFormContent }
            .map { it.toVideoItem() }
    }

    private const val GENERAL_TRENDING = "Trending"
    private val GENERAL_PARTS = listOf("trending_music", "trending_gaming", "trending_movies_and_shows", "trending_podcasts_episodes")

    /** Shorts from the given channels' Shorts tabs, newest first, mixed between channels. */
    suspend fun shorts(channels: List<Channel>, perChannel: Int = 8): List<VideoItem> = coroutineScope {
        val limit = Semaphore(4)
        channels.shuffled().take(12).map { channel ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    runCatching {
                        val info = ChannelInfo.getInfo(service, channel.url)
                        val tab = info.tabs.firstOrNull { ChannelTabs.SHORTS in it.contentFilters } ?: return@runCatching emptyList()
                        ChannelTabInfo.getInfo(service, tab).relatedItems
                            .filterIsInstance<StreamInfoItem>()
                            .take(perChannel)
                            .map { it.toVideoItem().copy(uploader = it.uploaderName?.ifBlank { null } ?: channel.name, uploaderUrl = channel.url, uploaderAvatar = channel.avatar) }
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten().shuffled()
    }

    /** Shorts found by searching, for when there are no subscriptions to draw from. */
    suspend fun searchShorts(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        searchInfo(query, YoutubeSearchQueryHandlerFactory.VIDEOS)
            .filterIsInstance<StreamInfoItem>()
            .filter { it.isShortFormContent }
            .map { it.toVideoItem() }
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
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

    class FeedResult(
        val videos: List<VideoItem>,
        /** Channels whose videos could not be loaded at all. */
        val failed: Int,
        val firstError: Throwable?,
    )

    /**
     * Latest uploads from the given channels, newest first. Tries each channel's RSS feed
     * first, which is quick, and falls back to the channel's video tab when YouTube
     * refuses the feed or it comes back empty.
     */
    suspend fun feed(channels: List<Channel>): FeedResult = coroutineScope {
        val limit = Semaphore(6)
        val results = channels.map { channel ->
            async(Dispatchers.IO) {
                limit.withPermit {
                    runCatching { channelUploads(channel) }
                }
            }
        }.awaitAll()

        val videos = results.mapNotNull { it.getOrNull() }
            .flatten()
            .distinctBy { it.url }
            .sortedByDescending { it.uploadedAt }
            .take(300)
        FeedResult(
            videos = videos,
            failed = results.count { it.isFailure },
            firstError = results.firstNotNullOfOrNull { it.exceptionOrNull() },
        )
    }

    private fun channelUploads(channel: Channel): List<VideoItem> {
        val fromRss = runCatching {
            FeedInfo.getInfo(service, channel.url).relatedItems
                .filterIsInstance<StreamInfoItem>()
                .filterNot { it.isShortFormContent }
                .map { it.toVideoItem().copy(uploaderAvatar = channel.avatar) }
        }.getOrNull()
        if (!fromRss.isNullOrEmpty()) return fromRss

        val page = channelBlocking(channel.url)
        return page.videos
    }

    suspend fun channel(url: String): ChannelPage = withContext(Dispatchers.IO) { channelBlocking(url) }

    private fun channelBlocking(url: String): ChannelPage {
        val info = ChannelInfo.getInfo(service, url)
        val channel = Channel(info.url, info.name, VideoItem.bestImage(info.avatars, 240))
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val videos = videosTab?.let { tab ->
            ChannelTabInfo.getInfo(service, tab).relatedItems
                .filterIsInstance<StreamInfoItem>()
                .map {
                    it.toVideoItem().copy(
                        uploader = it.uploaderName?.takeIf { name -> name.isNotBlank() } ?: channel.name,
                        uploaderUrl = it.uploaderUrl ?: channel.url,
                        uploaderAvatar = channel.avatar,
                    )
                }
        }.orEmpty()
        return ChannelPage(channel, info.subscriberCount, VideoItem.bestImage(info.banners, 1280), videos)
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
