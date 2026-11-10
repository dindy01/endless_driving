package com.endlessdrive.game

import android.content.Context

/** Persistent settings and stats (SharedPreferences, written with apply()). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var best: Long
        get() = sp.getLong(K_BEST, 0L)
        set(v) = sp.edit().putLong(K_BEST, v).apply()

    var soundOn: Boolean
        get() = sp.getBoolean(K_SOUND, true)
        set(v) = sp.edit().putBoolean(K_SOUND, v).apply()

    var vibrationOn: Boolean
        get() = sp.getBoolean(K_VIBRATION, true)
        set(v) = sp.edit().putBoolean(K_VIBRATION, v).apply()

    var totalRuns: Int
        get() = sp.getInt(K_RUNS, 0)
        set(v) = sp.edit().putInt(K_RUNS, v).apply()

    /** 0 = touch steering (default), 1 = tilt. */
    var controlScheme: Int
        get() = sp.getInt(K_CONTROLS, 0)
        set(v) = sp.edit().putInt(K_CONTROLS, v).apply()

    /** Graphics preset 0 low, 1 medium (default), 2 high. */
    var quality: Int
        get() = sp.getInt(K_QUALITY, 1)
        set(v) = sp.edit().putInt(K_QUALITY, v).apply()

    /** Returns true if [score] is a new best (and stores it). */
    fun submitScore(score: Long): Boolean {
        if (score <= best) return false
        best = score
        return true
    }

    companion object {
        const val FILE = "endless_drive"
        private const val K_BEST = "best"
        private const val K_SOUND = "sound"
        private const val K_VIBRATION = "vibration"
        private const val K_RUNS = "total_runs"
        private const val K_CONTROLS = "controls"
        private const val K_QUALITY = "quality"
    }
}
