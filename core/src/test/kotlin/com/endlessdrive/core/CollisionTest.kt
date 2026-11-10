package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollisionTest {
    private val input = Input()
    private fun world(): World = World(1L).also { it.suppressSpawningForTest() }
    private fun run(w: World, seconds: Float) {
        var t = 0f
        while (t < seconds && w.alive) { w.update(1f / 60f, input); t += 1f / 60f }
    }

    @Test
    fun sameLaneObstacleCrashesAdjacentLaneDoesNot() {
        val w = world()
        w.placeForTest(Kind.STOPPED_CAR, 1, 60.0)
        run(w, 4f)
        assertFalse(w.alive)
        val w2 = world()
        w2.placeForTest(Kind.STOPPED_CAR, 0, 60.0)
        run(w2, 4f)
        assertTrue(w2.alive)
    }

    @Test
    fun everyKindIsSolid() {
        for (k in 0 until Kind.COUNT) {
            if (k == Kind.SHOULDER_CAR) continue
            val w = world()
            w.placeForTest(k, 1, 60.0)
            run(w, 6f)
            assertFalse("kind $k should crash", w.alive)
        }
    }

    @Test
    fun guardRailCrashesShoulderOnlySlows() {
        val w = world()
        input.steer = 1f
        var t = 0f
        while (w.alive && t < 0.5f) { w.update(1f / 60f, input); t += 1f / 60f }
        assertTrue("onShoulder expected", w.player.onShoulder || !w.alive)
        // shoulder: hold position on shoulder and check slowdown
        val w2 = world()
        w2.player.x = 4.8f
        input.steer = 0f
        val v0 = w2.player.v
        run(w2, 2f)
        assertTrue(w2.alive)
        assertTrue("shoulder must slow the car", w2.player.v < v0 * 0.8f)
        // rail
        input.steer = 1f
        run(w2, 3f)
        assertFalse("rail must crash", w2.alive)
        input.steer = 0f
    }

    @Test
    fun shieldAbsorbsOneHitThenExpires() {
        val w = world()
        w.placePickupForTest(PickupKind.SHIELD, GameConfig.LANE1_X, 20.0)
        w.placeForTest(Kind.BARRIER, 1, 60.0)
        w.placeForTest(Kind.BARRIER, 1, 200.0)
        run(w, 1f)
        assertTrue(w.shieldTimer > 0f)
        run(w, 1.5f)
        assertTrue("shield absorbs first hit", w.alive)
        assertEquals(0f, w.shieldTimer, 0f)
        run(w, 8f)
        assertFalse("second hit crashes", w.alive)
        val w2 = world()
        w2.placePickupForTest(PickupKind.SHIELD, GameConfig.LANE1_X, 10.0)
        run(w2, 0.6f)
        assertTrue(w2.shieldTimer > 4f)
        run(w2, 5.2f)
        assertEquals(0f, w2.shieldTimer, 0f)
    }

    @Test
    fun reviveClearsAheadAndGivesInvulnerability() {
        val w = world()
        w.placeForTest(Kind.BARRIER, 1, 40.0)
        run(w, 3f)
        assertFalse(w.alive)
        w.revive(input)
        assertTrue(w.alive)
        assertTrue(w.invulnTimer > 0f)
        for (c in w.traffic.cars) if (c.active) assertTrue(c.s >= w.player.s + GameConfig.CONTINUE_CLEAR_AHEAD - 1e-6)
    }

    @Test
    fun trafficNeverRearEndsPlayer() {
        val w = world()
        input.brake = true
        w.placeForTest(Kind.CAR, 1, -12.0, 40f) // fast car right behind
        run(w, 4f)
        assertTrue(w.alive)
        input.brake = false
    }
}
