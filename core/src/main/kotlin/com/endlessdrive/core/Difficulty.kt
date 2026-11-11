package com.endlessdrive.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Difficulty as a pure function of run time t (seconds). Everything is monotonic and capped.
 *
 *   cruise(t) = V0 + (VMAX - V0) * (1 - e^(-t / TAU_V))                 (<= VMAX)
 *   level(t)  = 0                                   for t <= GRACE_S
 *             = min(LEVEL_CAP, 1 - e^(-(t - GRACE_S) / TAU_LEVEL))      otherwise
 *   gapTime(L) = GAP_TIME_EASY + (GAP_TIME_HARD - GAP_TIME_EASY) * L    (seconds between forced lane changes)
 */
object Difficulty {
    fun cruise(t: Float): Float {
        val tt = max(0f, t)
        return min(Cfg.VMAX, Cfg.V0 + (Cfg.VMAX - Cfg.V0) * (1f - exp(-tt / Cfg.TAU_V)))
    }

    fun level(t: Float): Float {
        if (t <= Cfg.GRACE_S) return 0f
        return min(Cfg.LEVEL_CAP, 1f - exp(-(t - Cfg.GRACE_S) / Cfg.TAU_LEVEL))
    }

    fun gapTime(level: Float): Float = Cfg.GAP_TIME_EASY + (Cfg.GAP_TIME_HARD - Cfg.GAP_TIME_EASY) * level

    /** Fastest the player can possibly travel when cruise speed is [cruise]. */
    fun maxPlayerSpeed(cruise: Float): Float = cruise * Cfg.NITRO_MULT

    /**
     * Minimum clear distance that must exist behind an obstacle moving at [obstacleSpeed] before the
     * nearest obstacle in the *other* lane. The first term is the hard, physically-needed distance
     * for a lane change at top (nitro) speed; the second is the comfortable gap for this level.
     */
    fun guard(cruise: Float, level: Float, obstacleSpeed: Float): Float {
        val hard = maxPlayerSpeed(cruise) * Cfg.T_STRUCT
        val comfy = (1.1f * cruise - obstacleSpeed) * gapTime(level)
        return max(hard, comfy) + 2f * Cfg.PLAYER_HALF_L + 2f
    }

    /** Probability that the next cluster is in the other lane (forces a lane change). */
    fun switchProb(level: Float): Float = 0.5f + 0.3f * level

    /** Probability that a cluster is static (stopped car, cones, barrier, roadworks). 0 in the grace period. */
    fun staticProb(level: Float): Float = if (level <= 0f) 0f else 0.06f + 0.2f * level

    fun maxClusterSize(level: Float): Int = 1 + (level * 2.6f).toInt()
}
