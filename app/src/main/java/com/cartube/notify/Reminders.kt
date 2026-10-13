package com.cartube.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cartube.R
import com.cartube.ui.PlayerActivity
import com.cartube.ui.StatsActivity
import com.cartube.youtube.VideoItem

/** The app's own notifications: new uploads and the daily watch-time reminder. */
object Reminders {
    private const val NEW_VIDEOS = "new_videos"
    private const val WATCH_TIME = "watch_time"
    private const val GROUP = "com.cartube.NEW_VIDEOS"

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(NEW_VIDEOS, context.getString(R.string.notify_channel_new), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(WATCH_TIME, context.getString(R.string.notify_channel_time), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun newVideo(context: Context, video: VideoItem, thumbnail: Bitmap?) {
        if (!allowed(context)) return
        channels(context)
        val id = video.url.hashCode()
        val open = PendingIntent.getActivity(
            context, id, video.putInto(Intent(context, PlayerActivity::class.java)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NEW_VIDEOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(video.uploader)
            .setContentText(video.title)
            .setLargeIcon(thumbnail)
            .setStyle(thumbnail?.let { NotificationCompat.BigPictureStyle().bigPicture(it).bigLargeIcon(null as Bitmap?) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun newVideosSummary(context: Context, count: Int) {
        if (!allowed(context) || count < 2) return
        channels(context)
        val summary = NotificationCompat.Builder(context, NEW_VIDEOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_new_summary, count))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(GROUP.hashCode(), summary)
    }

    fun watchTime(context: Context, todayMs: Long) {
        if (!allowed(context)) return
        channels(context)
        val open = PendingIntent.getActivity(
            context, 7, Intent(context, StatsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, WATCH_TIME)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_time_title))
            .setContentText(context.getString(R.string.notify_time_text, formatMinutes(todayMs)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(WATCH_TIME.hashCode(), notification)
    }

    fun formatMinutes(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }
}
