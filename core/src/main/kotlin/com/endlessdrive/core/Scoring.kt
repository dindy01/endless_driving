package com.endlessdrive.core

/** Score = metres driven + coins + near-miss bonuses. */
object Scoring {
    fun distancePoints(distance: Double): Long = (distance * GameConfig.POINTS_PER_METRE).toLong()

    fun total(distance: Double, bonus: Long): Long = distancePoints(distance) + bonus

    /** A near miss: passed within NEAR_MISS_GAP edge-to-edge while going fast enough. */
    fun isNearMiss(minGap: Float, speed: Float): Boolean =
        minGap < GameConfig.NEAR_MISS_GAP && speed >= GameConfig.NEAR_MISS_MIN_SPEED

    fun isCloseCall(minGap: Float): Boolean = minGap < GameConfig.CLOSE_CALL_GAP

    fun nearMissPoints(combo: Int, closeCall: Boolean): Int {
        val c = combo.coerceIn(1, GameConfig.MAX_COMBO)
        val base = GameConfig.NEAR_MISS_POINTS * c
        return if (closeCall) base * 3 / 2 else base
    }
}
