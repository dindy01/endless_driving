package com.endlessdrive.core

import kotlin.math.abs

/** Axis-aligned box tests on (x, z) centres and half extents. */
object Collision {
    fun overlaps(
        ax: Float, az: Float, aHalfW: Float, aHalfL: Float,
        bx: Float, bz: Float, bHalfW: Float, bHalfL: Float,
    ): Boolean = abs(ax - bx) < aHalfW + bHalfW && abs(az - bz) < aHalfL + bHalfL

    /** Player vs obstacle using the shrunk (fair) hitboxes used by the game. */
    fun playerHits(px: Float, pz: Float, kind: Int, ox: Float, oz: Float): Boolean {
        val s = GameConfig.HITBOX_SHRINK
        return overlaps(
            px, pz, GameConfig.PLAYER_HALF_W * s, GameConfig.PLAYER_HALF_L * s,
            ox, oz, ObstacleKind.HALF_W[kind] * s, ObstacleKind.HALF_L[kind] * s,
        )
    }

    /** Edge-to-edge lateral gap between player and obstacle (negative = overlapping laterally). */
    fun lateralGap(px: Float, kind: Int, ox: Float): Float =
        abs(px - ox) - (GameConfig.PLAYER_HALF_W + ObstacleKind.HALF_W[kind])
}
