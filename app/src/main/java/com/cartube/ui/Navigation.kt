package com.cartube.ui

import android.content.Context
import android.content.Intent
import com.cartube.youtube.Channel
import com.cartube.youtube.VideoItem

fun Context.openVideo(item: VideoItem) {
    startActivity(item.putInto(Intent(this, PlayerActivity::class.java)))
}

fun Context.openChannel(channel: Channel) {
    startActivity(channel.putInto(Intent(this, ChannelActivity::class.java)))
}

fun Context.openChannelOf(item: VideoItem) {
    val url = item.uploaderUrl ?: return
    openChannel(Channel(url, item.uploader, item.uploaderAvatar))
}

fun Context.shareVideo(item: VideoItem) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, item.title)
        .putExtra(Intent.EXTRA_TEXT, item.url)
    startActivity(Intent.createChooser(send, null))
}
