package com.cartube.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.cartube.R
import com.cartube.data.Settings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch gestures on the video: double-tap the left or right half to skip 10 seconds,
 * swipe up and down on the left half for brightness and on the right half for volume.
 * A single tap still shows and hides the controls.
 */
class PlayerGestures(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val indicator: TextView,
    private val player: () -> Player?,
) {
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hideIndicator = Runnable { indicator.isVisible = false }
    private var volume = -1f
    private var scaling = false

    /** Pinch out to fill the screen (cropping the edges), pinch in to see the whole picture. */
    private val scaleDetector = android.view.ScaleGestureDetector(
        activity,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var total = 1f

            override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                total = 1f
                scaling = true
                return true
            }

            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                total *= detector.scaleFactor
                return true
            }

            override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                val zoom = total > 1.08f
                val fit = total < 0.92f
                if (zoom || fit) {
                    playerView.resizeMode = if (zoom) {
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    show(activity.getString(if (zoom) R.string.gesture_zoom else R.string.gesture_fit))
                }
            }
        },
    )

    private val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            playerView.performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val p = player() ?: return false
            val forward = e.x > playerView.width / 2f
            val target = p.currentPosition + if (forward) SEEK_MS else -SEEK_MS
            val duration = p.duration
            p.seekTo(if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0))
            show(activity.getString(if (forward) R.string.gesture_forward else R.string.gesture_back))
            return true
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            val start = first ?: return false
            if (abs(current.y - start.y) < abs(current.x - start.x)) return false
            val fraction = dy / playerView.height.coerceAtLeast(1) * 1.5f
            if (start.x < playerView.width / 2f) changeBrightness(fraction) else changeVolume(fraction)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        playerView.setOnTouchListener { _, event ->
            if (!Settings.gestures) return@setOnTouchListener false
            scaleDetector.onTouchEvent(event)
            // While two fingers are down, don't also treat the movement as a swipe or tap.
            if (event.pointerCount > 1 || scaleDetector.isInProgress) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scaling = false
                return@setOnTouchListener true
            }
            if (scaling) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scaling = false
                return@setOnTouchListener true
            }
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                volume = -1f
            }
            true
        }
    }

    private fun changeBrightness(delta: Float) {
        val attrs = activity.window.attributes
        val currentLevel = if (attrs.screenBrightness < 0) 0.5f else attrs.screenBrightness
        attrs.screenBrightness = (currentLevel + delta).coerceIn(0.01f, 1f)
        activity.window.attributes = attrs
        show(activity.getString(R.string.gesture_brightness, (attrs.screenBrightness * 100).roundToInt()))
    }

    private fun changeVolume(delta: Float) {
        val am = audio ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (volume < 0) volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        volume = (volume + delta * max).coerceIn(0f, max.toFloat())
        am.setStreamVolume(AudioManager.STREAM_MUSIC, volume.roundToInt(), 0)
        show(activity.getString(R.string.gesture_volume, (volume / max * 100).roundToInt()))
    }

    private fun show(text: String) {
        indicator.text = text
        indicator.isVisible = true
        handler.removeCallbacks(hideIndicator)
        handler.postDelayed(hideIndicator, 700)
    }

    private companion object {
        const val SEEK_MS = 10_000L
    }
}
