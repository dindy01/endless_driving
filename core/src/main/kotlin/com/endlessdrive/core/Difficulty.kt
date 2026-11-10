package com.endlessdrive.core

import com.endlessdrive.core.GameConfig.DIFF_CAP
import com.endlessdrive.core.GameConfig.DIFF_TAU
import com.endlessdrive.core.GameConfig.GRACE_TIME
import com.endlessdrive.core.GameConfig.SPEED_TAU
import com.endlessdrive.core.GameConfig.V0
import com.endlessdrive.core.GameConfig.V1
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Capped, monotonic difficulty curves (documented in DECISIONS.md):
 *
 *   vBase(t)   = V0 + (V1 − V0)(1 − e^(−t/τv))                 25 → 45 m/s, τv = 90 s
 *   level(t)   = 0 for t ≤ 15 s, else min(0.95, 1 − e^(−(t−15)/τd)), τd = 80 s
 *   spacing(L) = 95 + (38 − 95)·L metres of player travel between spawn events
 *                (140 m during the grace period)
 */
object Difficulty {
    fun baseSpeed(t: Float): Float = min(V1, V0 + (V1 - V0) * (1f - exp(-max(0f, t) / SPEED_TAU)))

    fun level(t: Float): Float = if (t <= GRACE_TIME) 0f else min(DIFF_CAP, 1f - exp(-(t - GRACE_TIME) / DIFF_TAU))

    fun inGrace(t: Float) = t < GRACE_TIME

    fun spacing(t: Float): Float =
        if (inGrace(t)) GameConfig.SPACING_GRACE
        else GameConfig.SPACING_EASY + (GameConfig.SPACING_HARD - GameConfig.SPACING_EASY) * level(t)

    /** Weighted event choice for r in [0,1). Grace only spawns moving cars and trucks. */
    fun pickKind(level: Float, grace: Boolean, r: Float): Int {
        if (grace) return if (r < 0.85f) Kind.CAR else Kind.TRUCK
        val w0 = 0.46f
        val w1 = 0.12f + 0.06f * level
        val w2 = 0.07f + 0.05f * level
        val w3 = 0.06f + 0.04f * level
        val w4 = 0.06f + 0.04f * level
        val w5 = 0.03f + 0.07f * level
        var x = r * (w0 + w1 + w2 + w3 + w4 + w5)
        if (x < w0) return Kind.CAR
        x -= w0
        if (x < w1) return Kind.TRUCK
        x -= w1
        if (x < w2) return Kind.STOPPED_CAR
        x -= w2
        if (x < w3) return Kind.BARRIER
        x -= w3
        if (x < w4) return Kind.CONES
        return Kind.ROADWORKS
    }
}
