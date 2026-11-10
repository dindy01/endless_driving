package com.endlessdrive.core

import com.endlessdrive.core.GameConfig.DIFF_CAP
import com.endlessdrive.core.GameConfig.DIFF_TAU
import com.endlessdrive.core.GameConfig.GAP_EASY
import com.endlessdrive.core.GameConfig.GAP_HARD
import com.endlessdrive.core.GameConfig.GRACE_TIME
import com.endlessdrive.core.GameConfig.SPEED_TAU
import com.endlessdrive.core.GameConfig.V_MAX
import com.endlessdrive.core.GameConfig.V_MIN
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Difficulty curve (documented in DECISIONS.md). All functions are monotonic in t and capped.
 *
 *   speed(t) = V_MIN + (V_MAX - V_MIN) * (1 - e^(-t / SPEED_TAU))             (<= V_MAX)
 *   level(t) = 0                                       for t <= GRACE_TIME
 *            = min(DIFF_CAP, 1 - e^(-(t - GRACE_TIME) / DIFF_TAU))   otherwise
 *   gap(L)   = GAP_EASY + (GAP_HARD - GAP_EASY) * L                (free road between rows)
 *   p2(L)    = clamp(0.9 * (L - 0.2), 0, 0.6)       (chance a row blocks two lanes)
 */
object Difficulty {
    fun speed(t: Float): Float {
        val tt = max(0f, t)
        return min(V_MAX, V_MIN + (V_MAX - V_MIN) * (1f - exp(-tt / SPEED_TAU)))
    }

    fun level(t: Float): Float {
        if (t <= GRACE_TIME) return 0f
        return min(DIFF_CAP, 1f - exp(-(t - GRACE_TIME) / DIFF_TAU))
    }

    fun inGrace(t: Float): Boolean = t < GRACE_TIME

    fun gap(level: Float): Float = GAP_EASY + (GAP_HARD - GAP_EASY) * level

    fun doubleBlockChance(level: Float): Float = min(0.6f, max(0f, 0.9f * (level - 0.2f)))

    /** Weighted obstacle choice; r in [0,1). Grace period only uses the two most readable kinds. */
    fun pickKind(level: Float, grace: Boolean, r: Float): Int {
        if (grace) return if (r < 0.5f) ObstacleKind.BARRIER else ObstacleKind.SLOW_CAR
        val wCar = 0.38f
        val wBar = 0.30f
        val wOil = 0.10f + 0.12f * level
        val wTruck = 0.06f + 0.22f * level
        var x = r * (wCar + wBar + wOil + wTruck)
        if (x < wCar) return ObstacleKind.SLOW_CAR
        x -= wCar
        if (x < wBar) return ObstacleKind.BARRIER
        x -= wBar
        if (x < wOil) return ObstacleKind.OIL
        return ObstacleKind.TRUCK
    }
}
