package com.comii.app.data

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

/** Three-band bass shelf applied to the current ExoPlayer audio session. */
class BassEffectController {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var midBass = 0f
    private var bass = 0f
    private var ultraBass = 0f

    fun attach(sessionId: Int) {
        if (sessionId == -1 || equalizer != null) return
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
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
            listOf(32 to ultraBass, 60 to ultraBass, 120 to bass, 230 to bass, 500 to midBass).forEach { (target, value) ->
                val band = nearest(target) ?: return@forEach
                if (!used.add(band)) return@forEach
                runCatching { eq.setBandLevel(band.toShort(), level(value)) }
            }
            eq.enabled = ultraBass > 0f || bass > 0f || midBass > 0f

            // 超出均衡器上限的“更重”：硬件 BassBoost + 整体增益补偿
            runCatching {
                bassBoost?.let { boost ->
                    if (ultraBass <= 0f) {
                        boost.enabled = false
                    } else {
                        boost.setStrength((ultraBass.coerceIn(0f, 100f) * 10f).toInt().toShort())
                        boost.enabled = true
                    }
                }
            }
            runCatching {
                loudness?.let { ln ->
                    if (ultraBass > 55f) {
                        // 55 → 0mB，100 → 1500mB（约 +15dB）额外推力
                        ln.setTargetGain(((ultraBass - 55f) / 45f * 1500f).toInt())
                        ln.enabled = true
                    } else {
                        ln.enabled = false
                    }
                }
            }
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        bassBoost = null
        loudness = null
    }
}
