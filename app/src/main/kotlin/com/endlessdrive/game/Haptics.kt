package com.endlessdrive.game

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibration cues. Effects are created once; every call is safe on devices without a vibrator. */
class Haptics(context: Context) {
    @Volatile var enabled = true

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()?.takeIf { runCatching { it.hasVibrator() }.getOrDefault(false) }

    private val light: VibrationEffect? = if (Build.VERSION.SDK_INT >= 26) VibrationEffect.createOneShot(14, 80) else null
    private val medium: VibrationEffect? = if (Build.VERSION.SDK_INT >= 26) VibrationEffect.createOneShot(40, 170) else null
    private val heavy: VibrationEffect? =
        if (Build.VERSION.SDK_INT >= 26) VibrationEffect.createWaveform(longArrayOf(0, 90, 50, 160), intArrayOf(0, 255, 0, 140), -1) else null

    fun light() = buzz(light, 14)
    fun medium() = buzz(medium, 40)
    fun heavy() = buzz(heavy, 200)

    private fun buzz(effect: VibrationEffect?, fallbackMs: Long) {
        val v = vibrator ?: return
        if (!enabled) return
        try {
            if (Build.VERSION.SDK_INT >= 26 && effect != null) v.vibrate(effect) else @Suppress("DEPRECATION") v.vibrate(fallbackMs)
        } catch (_: Exception) {
            // haptics must never take the game down
        }
    }
}
