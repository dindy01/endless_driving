package com.endlessdrive.game

import android.content.Context

/** Everything that survives process death: best score, options and the lifetime run count. */
class Settings(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var best: Int
        get() = sp.getInt("best", 0)
        private set(v) = sp.edit().putInt("best", v).apply()

    /** false = on-screen touch steering (default), true = tilt. */
    var tilt: Boolean
        get() = sp.getBoolean("tilt", false)
        set(v) = sp.edit().putBoolean("tilt", v).apply()

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var vibration: Boolean
        get() = sp.getBoolean("vibration", true)
        set(v) = sp.edit().putBoolean("vibration", v).apply()

    /** 0 low, 1 medium (default), 2 high. */
    var quality: Int
        get() = sp.getInt("quality", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("quality", v.coerceIn(0, 2)).apply()

    var totalRuns: Int
        get() = sp.getInt("runs", 0)
        set(v) = sp.edit().putInt("runs", v).apply()

    /** Stores [score] if it beats the best; returns true when it did. */
    fun submit(score: Int): Boolean {
        if (score <= best) return false
        best = score
        return true
    }

    companion object {
        const val FILE = "drive"
    }
}
