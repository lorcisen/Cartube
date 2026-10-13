package com.cartube.player

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import com.cartube.data.Settings

/**
 * Optional audio processing on the player's audio session:
 * - "Jämn volym": a gentle compressor and limiter so quiet and loud videos sound alike.
 * - "Tydligare tal": lifts the speech frequencies and trims rumble, for podcasts.
 */
class AudioEffects {
    private var effect: DynamicsProcessing? = null
    private var session = 0

    fun apply(audioSessionId: Int) {
        session = audioSessionId
        release()
        val level = Settings.normalizeVolume
        val speech = Settings.clearSpeech
        if (audioSessionId == 0 || (!level && !speech)) return
        effect = runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                speech, 4,
                level, 1,
                false, 0,
                level,
            )
            if (speech) {
                val eq = DynamicsProcessing.Eq(true, true, 4)
                eq.setBand(0, DynamicsProcessing.EqBand(true, 150f, -4f))
                eq.setBand(1, DynamicsProcessing.EqBand(true, 1000f, 0f))
                eq.setBand(2, DynamicsProcessing.EqBand(true, 4000f, 5f))
                eq.setBand(3, DynamicsProcessing.EqBand(true, 20000f, 1f))
                config.setPreEqAllChannelsTo(eq)
            }
            if (level) {
                val mbc = DynamicsProcessing.Mbc(true, true, 1)
                mbc.setBand(
                    0,
                    DynamicsProcessing.MbcBand(true, 20000f, 5f, 120f, 3f, -28f, 6f, -90f, 1f, 0f, 7f),
                )
                config.setMbcAllChannelsTo(mbc)
                config.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1.5f, 0f),
                )
            }
            DynamicsProcessing(0, audioSessionId, config.build()).apply { enabled = true }
        }.onFailure { Log.w("AudioEffects", "Could not enable audio effects", it) }.getOrNull()
    }

    /** Re-applies after the settings changed. */
    fun refresh() = apply(session)

    fun release() {
        runCatching { effect?.release() }
        effect = null
    }
}
