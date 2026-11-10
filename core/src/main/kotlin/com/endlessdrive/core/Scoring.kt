package com.endlessdrive.core

/** Score = distance points + bonuses (coins, near misses). */
object Scoring {
    fun distancePoints(distance: Float): Long = (distance * GameConfig.POINTS_PER_UNIT).toLong()

    fun nearMissPoints(combo: Int, closeCall: Boolean): Int {
        val c = combo.coerceIn(1, GameConfig.MAX_COMBO)
        return GameConfig.NEAR_MISS_POINTS * c * (if (closeCall) 2 else 1)
    }

    fun isNearMiss(minEdgeGap: Float): Boolean = minEdgeGap < GameConfig.NEAR_MISS_MARGIN

    fun isCloseCall(minEdgeGap: Float): Boolean = minEdgeGap < GameConfig.CLOSE_CALL_GAP

    fun total(distance: Float, bonus: Long): Long = distancePoints(distance) + bonus
}
