package com.endlessdrive.game

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short haptic cues. Effects are pre-built; all calls are fail-safe. */
class Haptics(context: Context) {
    var enabled = true

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }

    private val effects: Array<Any?> = arrayOfNulls(3)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            effects[TICK] = VibrationEffect.createOneShot(12, 90)
            effects[BUMP] = VibrationEffect.createOneShot(35, 180)
            effects[CRASH] = VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 120), intArrayOf(0, 255, 0, 160), -1)
        }
    }

    fun tick() = play(TICK, 12)
    fun bump() = play(BUMP, 35)
    fun crash() = play(CRASH, 160)

    private fun play(which: Int, legacyMs: Long) {
        if (!enabled) return
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(effects[which] as VibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(legacyMs)
            }
        } catch (_: Exception) {
            // Never let haptics crash the game.
        }
    }

    private companion object {
        const val TICK = 0
        const val BUMP = 1
        const val CRASH = 2
    }
}
