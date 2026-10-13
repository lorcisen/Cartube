package com.cartube.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.imageLoader
import coil.request.ImageRequest
import com.cartube.data.Feed
import com.cartube.data.Library
import com.cartube.data.Settings
import java.util.concurrent.TimeUnit

/**
 * Checks the subscribed channels for new uploads a few times a day and posts a notification
 * for each new video from channels whose bell is on. The first run only remembers what is
 * there, so turning this on doesn't flood the phone.
 */
class NewVideosWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Settings.notifyNewVideos || Library.subscriptions().isEmpty()) return Result.success()
        if (com.cartube.net.RateLimit.active) return Result.retry()
        val prefs = applicationContext.getSharedPreferences("new_videos", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
        val firstRun = !prefs.getBoolean("initialised", false)

        val videos = runCatching { Feed.load(force = true).videos }.getOrElse { return Result.retry() }
        val muted = ChannelBells.muted()
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val fresh = videos.filter { it.url !in seen && it.uploadedAt > weekAgo && it.uploaderUrl !in muted }

        if (!firstRun) {
            val toShow = fresh.sortedByDescending { it.uploadedAt }.take(MAX_NOTIFICATIONS)
            for (video in toShow) Reminders.newVideo(applicationContext, video, thumbnail(video.thumbnail))
            Reminders.newVideosSummary(applicationContext, toShow.size)
        }
        seen += videos.map { it.url }
        prefs.edit()
            .putStringSet("seen", seen.toList().takeLast(1000).toSet())
            .putBoolean("initialised", true)
            .apply()
        return Result.success()
    }

    private suspend fun thumbnail(url: String?): Bitmap? = url?.let {
        val request = ImageRequest.Builder(applicationContext).data(it).size(640, 360).allowHardware(false).build()
        (applicationContext.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
    }

    companion object {
        private const val NAME = "new_videos"
        private const val MAX_NOTIFICATIONS = 5

        /** Schedules (or cancels) the check to match the setting. */
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            if (!Settings.notifyNewVideos) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<NewVideosWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
