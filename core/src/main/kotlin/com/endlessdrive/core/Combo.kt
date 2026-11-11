package com.endlessdrive.core

/** Near-miss combo: consecutive near misses inside COMBO_WINDOW raise the multiplier (capped). */
class Combo {
    var count = 0
        private set
    var timer = 0f
        private set
    var best = 0
        private set

    val multiplier: Int get() = if (count < 1) 1 else if (count > Cfg.COMBO_MAX) Cfg.COMBO_MAX else count

    fun reset() {
        count = 0; timer = 0f; best = 0
    }

    fun update(dt: Float) {
        if (timer > 0f) {
            timer -= dt
            if (timer <= 0f) {
                timer = 0f
                count = 0
            }
        }
    }

    /** Registers a near miss and returns the points awarded. */
    fun onNearMiss(): Int {
        count++
        timer = Cfg.COMBO_WINDOW
        if (count > best) best = count
        return Cfg.NEAR_MISS_POINTS * multiplier
    }

    fun breakCombo() {
        count = 0; timer = 0f
    }
}

object Collision {
    /** Axis-aligned overlap in road space (s along the road, x across), slightly forgiving. */
    fun overlaps(ds: Float, dx: Float, halfL: Float, halfW: Float): Boolean {
        val l = (halfL + Cfg.PLAYER_HALF_L) * 0.96f
        val w = (halfW + Cfg.PLAYER_HALF_W) * 0.94f
        return ds > -l && ds < l && dx > -w && dx < w
    }

    /** Edge-to-edge lateral gap between the player and an obstacle (negative = overlapping laterally). */
    fun lateralGap(dx: Float, halfW: Float): Float = (if (dx < 0f) -dx else dx) - halfW - Cfg.PLAYER_HALF_W

    fun hitsRail(x: Float): Boolean = (if (x < 0f) -x else x) + Cfg.PLAYER_HALF_W > Cfg.RAIL_X

    fun onShoulder(x: Float): Boolean = (if (x < 0f) -x else x) + Cfg.PLAYER_HALF_W * 0.5f > Cfg.ROAD_HALF
}
