package com.cartube.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cartube.R
import com.cartube.data.Downloads
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Asks for a quality and starts a download. */
object DownloadDialog {
    fun show(activity: AppCompatActivity, item: VideoItem) {
        activity.lifecycleScope.launch {
            val info = runCatching { YouTube.streamInfo(item.url) }.getOrElse {
                Toast.makeText(activity, activity.getString(R.string.download_failed, it.message), Toast.LENGTH_LONG).show()
                return@launch
            }
            val heights = Downloads.heights(info)
            val labels = heights.map { "${it}p" } + activity.getString(R.string.download_audio)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.download_choose)
                .setItems(labels.toTypedArray()) { _, which ->
                    runCatching { Downloads.start(activity, info, item, heights.getOrNull(which)) }
                        .onSuccess { Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show() }
                        .onFailure {
                            Toast.makeText(activity, activity.getString(R.string.download_failed, it.message), Toast.LENGTH_LONG).show()
                        }
                }
                .show()
        }
    }
}
