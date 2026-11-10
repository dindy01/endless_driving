package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {
    private val input = Input()
    private fun world() = World(5L).also { it.suppressSpawningForTest() }
    private fun run(w: World, s: Float) { var t = 0f; while (t < s && w.alive) { w.update(1f / 60f, input); t += 1f / 60f } }

    @Test
    fun formulae() {
        assertEquals(100L, Scoring.total(100.0, 0))
        assertEquals(150L, Scoring.total(100.0, 50))
        assertEquals(50, Scoring.nearMissPoints(1, false))
        assertEquals(75, Scoring.nearMissPoints(1, true))
        assertEquals(150, Scoring.nearMissPoints(3, false))
        assertEquals(400, Scoring.nearMissPoints(99, false))
        assertTrue(Scoring.isNearMiss(0.5f, 30f))
        assertFalse("too slow", Scoring.isNearMiss(0.5f, 10f))
        assertFalse("too far", Scoring.isNearMiss(1.5f, 30f))
    }

    @Test
    fun centredLanesAreNotANearMissButHuggingIs() {
        val w = world()
        w.placeForTest(Kind.STOPPED_CAR, 0, 60.0)
        run(w, 4f)
        assertEquals("centred adjacent lane = 1.66 m gap", 0, w.nearMisses)
        val w2 = world()
        w2.player.x = 0.9f // hug the lane line
        w2.placeForTest(Kind.STOPPED_CAR, 0, 60.0)
        w2.placeForTest(Kind.STOPPED_CAR, 0, 120.0)
        run(w2, 7f)
        assertTrue(w2.alive)
        assertEquals(2, w2.nearMisses)
        assertEquals(2, w2.combo)
        assertEquals(50L + 100L, w2.bonus)
        assertEquals(Scoring.distancePoints(w2.distance) + 150L, w2.score)
    }

    @Test
    fun comboExpires() {
        val w = world()
        w.player.x = 0.9f
        w.placeForTest(Kind.STOPPED_CAR, 0, 40.0)
        run(w, 2.5f)
        assertEquals(1, w.combo)
        run(w, GameConfig.COMBO_WINDOW + 0.2f)
        assertEquals(0, w.combo)
    }

    @Test
    fun coins() {
        val w = world()
        w.placePickupForTest(PickupKind.COIN, GameConfig.LANE1_X, 30.0)
        w.placePickupForTest(PickupKind.COIN, GameConfig.LANE1_X, 40.0)
        w.placePickupForTest(PickupKind.COIN, GameConfig.LANE0_X, 40.0)
        run(w, 3f)
        assertEquals(2, w.coins)
        assertEquals(2L * GameConfig.COIN_POINTS, w.bonus)
    }
}
