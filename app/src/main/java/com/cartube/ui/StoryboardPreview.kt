package com.cartube.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.Frameset

/**
 * Flips through a video's storyboard (the small preview frames YouTube shows when
 * scrubbing) in an ImageView, giving a quick glimpse of the whole video.
 */
class StoryboardPreview(private val view: ImageView, private val scope: CoroutineScope) {
    private var job: Job? = null

    fun play(framesets: List<Frameset>) {
        stop()
        // The largest frames that still come in a reasonable number of sprite sheets.
        val set = framesets.filter { it.urls.isNotEmpty() && it.totalCount > 0 }
            .maxByOrNull { it.frameWidth } ?: return
        val perPage = set.framesPerPageX * set.framesPerPageY
        if (perPage <= 0) return
        val sheets = HashMap<Int, Bitmap>()
        job = scope.launch {
            // About 24 evenly spread frames, shown three per second.
            val step = maxOf(1, set.totalCount / 24)
            var frame = 0
            while (isActive) {
                val sheetIndex = frame / perPage
                val sheet = sheets[sheetIndex] ?: load(set.urls.getOrNull(sheetIndex) ?: break)
                    ?.also { sheets[sheetIndex] = it }
                if (sheet != null) {
                    val inSheet = frame % perPage
                    val x = (inSheet % set.framesPerPageX) * set.frameWidth
                    val y = (inSheet / set.framesPerPageX) * set.frameHeight
                    if (x + set.frameWidth <= sheet.width && y + set.frameHeight <= sheet.height) {
                        view.setImageBitmap(Bitmap.createBitmap(sheet, x, y, set.frameWidth, set.frameHeight))
                    }
                }
                delay(330)
                frame += step
                if (frame >= set.totalCount) frame = 0
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun load(url: String): Bitmap? {
        val request = ImageRequest.Builder(view.context).data(url).allowHardware(false).build()
        return (view.context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
    }
}
