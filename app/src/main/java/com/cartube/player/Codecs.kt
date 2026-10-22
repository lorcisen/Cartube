package com.cartube.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Which of YouTube's video codecs (H.264, VP9, AV1) this device can decode, and how. */
object Codecs {
    private val decoders: List<MediaCodecInfo> by lazy {
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder } }
            .getOrDefault(emptyList())
    }
    private val cache = HashMap<String, Pair<Boolean, Boolean>>()

    /** True when some decoder handles [codec] ("avc1.640028", "vp9", "av01.0.08M.08"…). */
    fun canDecode(codec: String?): Boolean = support(codec).first

    fun hasHardware(codec: String?): Boolean = support(codec).second

    @Synchronized
    private fun support(codec: String?): Pair<Boolean, Boolean> {
        val mime = mimeOf(codec) ?: return true to true // Unknown: let the player try.
        return cache.getOrPut(mime) {
            val matching = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            matching.isNotEmpty() to matching.any { isHardware(it) }
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }

    private fun mimeOf(codec: String?): String? {
        val c = codec?.lowercase() ?: return null
        return when {
            c.startsWith("avc") -> "video/avc"
            c.startsWith("vp9") || c.startsWith("vp09") -> "video/x-vnd.on2.vp9"
            c.startsWith("av01") -> "video/av01"
            else -> null
        }
    }
}
