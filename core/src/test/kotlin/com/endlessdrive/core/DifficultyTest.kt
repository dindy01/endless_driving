package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyTest {
    @Test
    fun cruiseIsMonotonicAndCapped() {
        var prev = Difficulty.cruise(0f)
        assertEquals(Cfg.V0, prev, 1e-4f)
        var t = 0f
        while (t <= 7200f) {
            val v = Difficulty.cruise(t)
            assertTrue("monotonic at $t", v >= prev - 1e-5f)
            assertTrue("capped at $t", v <= Cfg.VMAX)
            prev = v
            t += 0.5f
        }
        assertTrue("approaches the cap", Difficulty.cruise(7200f) > Cfg.VMAX - 0.01f)
    }

    @Test
    fun levelIsZeroInGraceThenMonotonicAndCapped() {
        var t = 0f
        while (t <= Cfg.GRACE_S) { assertEquals(0f, Difficulty.level(t), 0f); t += 0.25f }
        var prev = 0f
        while (t <= 7200f) {
            val l = Difficulty.level(t)
            assertTrue(l >= prev - 1e-6f)
            assertTrue(l <= Cfg.LEVEL_CAP)
            prev = l
            t += 0.5f
        }
        assertEquals(Cfg.LEVEL_CAP, Difficulty.level(7200f), 1e-6f)
    }

    @Test
    fun derivedKnobsAreMonotonicInLevelAndBounded() {
        var l = 0f
        var gap = Difficulty.gapTime(0f)
        var sw = Difficulty.switchProb(0f)
        var st = Difficulty.staticProb(0f)
        var cl = Difficulty.maxClusterSize(0f)
        assertEquals("no static hazards during grace", 0f, st, 0f)
        assertEquals("single vehicles during grace", 1, cl)
        while (l <= 1f) {
            assertTrue(Difficulty.gapTime(l) <= gap + 1e-6f); gap = Difficulty.gapTime(l)
            assertTrue(Difficulty.switchProb(l) >= sw - 1e-6f); sw = Difficulty.switchProb(l)
            assertTrue(Difficulty.staticProb(l) >= st - 1e-6f); st = Difficulty.staticProb(l)
            assertTrue(Difficulty.maxClusterSize(l) >= cl); cl = Difficulty.maxClusterSize(l)
            assertTrue(gap >= Cfg.GAP_TIME_HARD - 1e-6f && sw <= 0.8001f && st <= 0.2601f && cl <= 3)
            l += 0.01f
        }
    }

    @Test
    fun guardAlwaysCoversAHardLaneChangeAtNitroSpeed() {
        var t = 0f
        while (t < 2000f) {
            val c = Difficulty.cruise(t)
            for (u in floatArrayOf(0f, 0.34f * c, 0.58f * c)) {
                val g = Difficulty.guard(c, Difficulty.level(t), u)
                assertTrue(g >= c * Cfg.NITRO_MULT * Cfg.T_STRUCT + 2 * Cfg.PLAYER_HALF_L)
            }
            t += 3f
        }
    }

    @Test
    fun trafficIsAlwaysSlowerThanTheSlowestPlayer() {
        assertTrue(Cfg.TRAFFIC_MAX_FACTOR * 1.05f < Cfg.SLOW_FACTOR)
    }
}
