package com.cartube.ui

import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.cartube.R
import com.cartube.data.Library
import com.cartube.databinding.SheetVideoActionsBinding
import com.cartube.player.PlayQueue
import com.cartube.youtube.Channel
import com.cartube.youtube.VideoItem
import com.cartube.youtube.YouTube
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** The sheet shown on a long press on any video: preview plus everything you can do with it. */
object VideoActions {

    fun show(activity: AppCompatActivity, item: VideoItem, onHidden: (() -> Unit)? = null) {
        val binding = SheetVideoActionsBinding.inflate(LayoutInflater.from(activity))
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(binding.root)

        binding.title.text = item.title
        binding.meta.text = activity.videoMeta(item)
        binding.preview.load(item.thumbnail)
        binding.preview.setOnClickListener { sheet.dismiss(); activity.openVideo(item) }

        val preview = StoryboardPreview(binding.preview, activity.lifecycleScope)
        activity.lifecycleScope.launch {
            val info = runCatching { YouTube.streamInfo(item.url) }.getOrNull() ?: return@launch
            if (sheet.isShowing) preview.play(info.previewFrames)
        }
        sheet.setOnDismissListener { preview.stop() }

        fun action(view: android.view.View, block: () -> Unit) = view.setOnClickListener {
            sheet.dismiss()
            block()
        }
        action(binding.playNext) {
            PlayQueue.playNext(item)
            Toast.makeText(activity, R.string.queued_next, Toast.LENGTH_SHORT).show()
        }
        action(binding.addToQueue) {
            PlayQueue.add(item)
            Toast.makeText(activity, R.string.queued, Toast.LENGTH_SHORT).show()
        }
        action(binding.watchLater) { toggleLater(activity, Library.WATCH_LATER, item) }
        action(binding.listenLater) { toggleLater(activity, Library.LISTEN_LATER, item) }
        action(binding.addToPlaylist) { pickPlaylist(activity, item) }
        action(binding.download) { DownloadDialog.show(activity, item) }
        action(binding.share) { activity.shareVideo(item) }
        action(binding.notInterested) {
            Library.markNotInterested(item)
            onHidden?.invoke()
            Toast.makeText(activity, R.string.not_interested_done, Toast.LENGTH_SHORT).show()
        }
        val channelUrl = item.uploaderUrl
        binding.blockChannel.isVisible = channelUrl != null
        binding.blockChannel.text = activity.getString(R.string.block_channel, item.uploader)
        action(binding.blockChannel) {
            Library.blockChannel(Channel(channelUrl ?: return@action, item.uploader, item.uploaderAvatar))
            onHidden?.invoke()
            Toast.makeText(activity, activity.getString(R.string.blocked_done, item.uploader), Toast.LENGTH_SHORT).show()
        }
        sheet.show()
    }

    fun toggleLater(activity: AppCompatActivity, list: String, item: VideoItem) {
        val added = Library.toggleLater(list, item)
        val message = when {
            !added -> R.string.removed_later
            list == Library.WATCH_LATER -> R.string.added_watch_later
            else -> R.string.added_listen_later
        }
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    fun pickPlaylist(activity: AppCompatActivity, item: VideoItem) {
        val playlists = Library.playlists().filter { it.sourceUrl == null }
        val names = (playlists.map { it.name } + activity.getString(R.string.playlist_new)).toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.add_to_playlist)
            .setItems(names) { _, which ->
                if (which < playlists.size) {
                    Library.addToPlaylist(playlists[which].id, item)
                    Toast.makeText(activity, activity.getString(R.string.added_to_playlist, playlists[which].name), Toast.LENGTH_SHORT).show()
                } else {
                    askName(activity) { name ->
                        Library.createPlaylist(name, item)
                        Toast.makeText(activity, activity.getString(R.string.added_to_playlist, name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    fun askName(activity: AppCompatActivity, initial: String = "", onName: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(initial)
            hint = activity.getString(R.string.playlist_name)
            setSingleLine()
        }
        val container = android.widget.FrameLayout(activity).apply {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.playlist_new)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
