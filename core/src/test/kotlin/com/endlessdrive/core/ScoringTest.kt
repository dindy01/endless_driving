package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {
    @Test
    fun distanceAndBonus() {
        assertEquals(0L, Scoring.total(0f, 0))
        assertEquals(200L, Scoring.total(100f, 0))
        assertEquals(250L, Scoring.total(100f, 50))
    }

    @Test
    fun nearMissPointsScaleWithComboAndCloseness() {
        assertEquals(25, Scoring.nearMissPoints(1, false))
        assertEquals(50, Scoring.nearMissPoints(1, true))
        assertEquals(75, Scoring.nearMissPoints(3, false))
        assertEquals(125, Scoring.nearMissPoints(99, false)) // combo capped at 5
    }

    @Test
    fun nearMissMarginIsAdjacentLaneOnly() {
        val adjacent = Collision.lateralGap(0f, ObstacleKind.SLOW_CAR, GameConfig.LANE_W)
        val twoAway = Collision.lateralGap(0f, ObstacleKind.SLOW_CAR, 2 * GameConfig.LANE_W)
        assertTrue(Scoring.isNearMiss(adjacent))
        assertFalse(Scoring.isNearMiss(twoAway))
    }

    private fun world(): World = World(3L).also { it.disableSpawningForTest() }
    private fun run(w: World, s: Float) { var t = 0f; while (t < s) { w.update(1f / 60f); t += 1f / 60f } }

    @Test
    fun passingAdjacentObstacleAwardsNearMissAndCombo() {
        val w = world()
        w.placeObstacleForTest(ObstacleKind.BARRIER, 0, 4f)
        w.placeObstacleForTest(ObstacleKind.BARRIER, 2, 8f)
        run(w, 2.5f)
        assertTrue(w.alive)
        assertEquals(2, w.nearMisses)
        assertEquals(2, w.combo)
        assertEquals(25L + 50L, w.bonus)
        assertEquals(Scoring.distancePoints(w.distance) + 75L, w.score)
    }

    @Test
    fun obstacleTwoLanesAwayIsNotANearMiss() {
        val w = world()
        w.requestLane(-1)
        w.placeObstacleForTest(ObstacleKind.BARRIER, 2, 6f)
        run(w, 2f)
        assertEquals(0, w.nearMisses)
    }

    @Test
    fun comboResetsAfterWindow() {
        val w = world()
        w.placeObstacleForTest(ObstacleKind.BARRIER, 0, 4f)
        run(w, 1.2f)
        assertEquals(1, w.combo)
        run(w, GameConfig.COMBO_WINDOW + 0.1f)
        assertEquals(0, w.combo)
    }

    @Test
    fun coinsAddPoints() {
        val w = world()
        w.placePickupForTest(PickupKind.COIN, 1, 3f)
        w.placePickupForTest(PickupKind.COIN, 1, 4f)
        w.placePickupForTest(PickupKind.COIN, 0, 4f) // other lane, not collected
        run(w, 1.5f)
        assertEquals(2, w.coins)
        assertEquals(2L * GameConfig.COIN_POINTS, w.bonus)
    }
}
