package com.cartube.ui

import android.content.Context
import android.icu.text.CompactDecimalFormat
import android.text.format.DateUtils
import com.cartube.R
import com.cartube.youtube.VideoItem
import java.util.Locale

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatCount(count: Long): String =
    CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
        .format(count)

fun formatAge(millis: Long): String {
    val now = System.currentTimeMillis()
    if (now - millis in 0 until DateUtils.MINUTE_IN_MILLIS) return "just nu"
    return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS)
        .toString()
        .replaceFirstChar { it.lowercase() }
}

/** "Channel · 12K visningar · 3 dagar sedan", leaving out what is unknown. */
fun Context.videoMeta(item: VideoItem, includeUploader: Boolean = true): String = listOfNotNull(
    item.uploader.takeIf { includeUploader && it.isNotBlank() },
    item.views.takeIf { it >= 0 }?.let { getString(R.string.views, formatCount(it)) },
    item.uploadedAt.takeIf { it > 0 }?.let { formatAge(it) },
).joinToString(" · ")
