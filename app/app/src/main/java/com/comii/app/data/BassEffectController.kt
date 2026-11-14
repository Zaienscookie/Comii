package com.comii.app.data

import android.media.audiofx.Equalizer

/** Three-band bass shelf applied to the current ExoPlayer audio session. */
class BassEffectController {
    private var equalizer: Equalizer? = null
    private var midBass = 0f
    private var bass = 0f
    private var ultraBass = 0f

    fun attach(sessionId: Int) {
        if (sessionId == -1 || equalizer != null) return
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        apply()
    }

    fun update(mid: Float, low: Float, ultra: Float) {
        midBass = mid
        bass = low
        ultraBass = ultra
        apply()
    }

    private fun apply() {
        val eq = equalizer ?: return
        runCatching {
            val bands = (0 until eq.numberOfBands.toInt()).map { index ->
                val range = eq.getBandFreqRange(index.toShort())
                index to ((range[0] + range[1]) / 2).toInt()
            }
            fun nearest(target: Int): Int? = bands.minByOrNull { kotlin.math.abs(it.second - target) }?.first
            fun level(value: Float): Short = (value.coerceIn(0f, 100f) * 15f).toInt().toShort()
            val used = mutableSetOf<Int>()
            listOf(60 to ultraBass, 230 to bass, 900 to midBass).forEach { (target, value) ->
                val band = nearest(target) ?: return@forEach
                if (!used.add(band)) return@forEach
                runCatching { eq.setBandLevel(band.toShort(), level(value)) }
            }
            eq.enabled = ultraBass > 0f || bass > 0f || midBass > 0f
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
    }
}
