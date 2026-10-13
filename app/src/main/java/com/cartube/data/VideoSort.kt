package com.cartube.data

import androidx.annotation.StringRes
import com.cartube.R
import com.cartube.youtube.VideoItem

/** Orders a list of videos. [ORIGINAL] keeps the list's own order (relevance, date added...). */
enum class VideoSort(@StringRes val label: Int) {
    ORIGINAL(R.string.sort_original),
    NEWEST(R.string.sort_newest),
    OLDEST(R.string.sort_oldest),
    MOST_VIEWED(R.string.sort_most_viewed),
    LONGEST(R.string.sort_longest),
    SHORTEST(R.string.sort_shortest),
    TITLE(R.string.sort_title),
    CHANNEL(R.string.sort_channel);

    fun apply(videos: List<VideoItem>): List<VideoItem> = when (this) {
        ORIGINAL -> videos
        // Unknown dates, views and lengths go last whichever way the list is sorted.
        NEWEST -> videos.sortedWith(compareBy<VideoItem> { it.uploadedAt <= 0 }.thenByDescending { it.uploadedAt })
        OLDEST -> videos.sortedWith(compareBy<VideoItem> { it.uploadedAt <= 0 }.thenBy { it.uploadedAt })
        MOST_VIEWED -> videos.sortedByDescending { it.views }
        LONGEST -> videos.sortedWith(compareBy<VideoItem> { it.durationSec <= 0 }.thenByDescending { it.durationSec })
        SHORTEST -> videos.sortedWith(compareBy<VideoItem> { it.durationSec <= 0 }.thenBy { it.durationSec })
        TITLE -> videos.sortedBy { it.title.lowercase() }
        CHANNEL -> videos.sortedWith(compareBy<VideoItem> { it.uploader.lowercase() }.thenByDescending { it.uploadedAt })
    }
}

/** Search filter on upload time. */
enum class UploadFilter(@StringRes val label: Int, private val maxAgeMs: Long) {
    ANY(R.string.filter_any_time, Long.MAX_VALUE),
    HOUR(R.string.filter_hour, 60 * 60 * 1000L),
    TODAY(R.string.filter_today, 24 * 60 * 60 * 1000L),
    WEEK(R.string.filter_week, 7 * 24 * 60 * 60 * 1000L),
    MONTH(R.string.filter_month, 31 * 24 * 60 * 60 * 1000L),
    YEAR(R.string.filter_year, 366 * 24 * 60 * 60 * 1000L);

    fun matches(video: VideoItem): Boolean =
        this == ANY || (video.uploadedAt > 0 && System.currentTimeMillis() - video.uploadedAt <= maxAgeMs)
}

/** Search filter on video length. */
enum class LengthFilter(@StringRes val label: Int, private val range: LongRange) {
    ANY(R.string.filter_any_length, 0L..Long.MAX_VALUE),
    SHORT(R.string.filter_short, 1L until 4 * 60),
    MEDIUM(R.string.filter_medium, 4L * 60..20 * 60),
    LONG(R.string.filter_long, 20L * 60 + 1..Long.MAX_VALUE);

    fun matches(video: VideoItem): Boolean = this == ANY || video.durationSec in range
}
