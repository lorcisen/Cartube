package com.cartube.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object YouTube {
    private val service get() = ServiceList.YouTube

    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map {
                VideoItem(
                    url = it.url,
                    title = it.name,
                    uploader = it.uploaderName.orEmpty(),
                    thumbnail = VideoItem.bestThumbnail(it.thumbnails),
                    durationSec = it.duration,
                )
            }
    }

    suspend fun streamInfo(url: String): StreamInfo = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(service, url)
    }

    fun StreamInfo.toVideoItem() = VideoItem(
        url = url,
        title = name,
        uploader = uploaderName.orEmpty(),
        thumbnail = VideoItem.bestThumbnail(thumbnails),
        durationSec = duration,
    )

    private val linkPattern = Regex(
        """https?://(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/\S+""",
        RegexOption.IGNORE_CASE,
    )

    /** Finds a YouTube link in shared text such as "Check this out: https://youtu.be/...". */
    fun findLink(text: String?): String? = text?.let { linkPattern.find(it)?.value }
}
