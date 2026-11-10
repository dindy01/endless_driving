package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyTest {
    @Test
    fun speedIsMonotonicAndCapped() {
        var prev = Difficulty.speed(0f)
        assertEquals(GameConfig.V_MIN, prev, 1e-4f)
        var t = 0f
        while (t < 7200f) {
            val s = Difficulty.speed(t)
            assertTrue("speed decreased at $t", s >= prev)
            assertTrue("speed above cap at $t", s <= GameConfig.V_MAX)
            prev = s
            t += 0.05f
        }
        assertEquals(GameConfig.V_MAX, Difficulty.speed(1e6f), 1e-3f)
    }

    @Test
    fun levelIsZeroInGraceThenMonotonicAndCapped() {
        var t = 0f
        while (t <= GameConfig.GRACE_TIME) {
            assertEquals(0f, Difficulty.level(t), 0f)
            t += 0.05f
        }
        var prev = 0f
        t = 0f
        while (t < 7200f) {
            val l = Difficulty.level(t)
            assertTrue(l >= prev)
            assertTrue(l <= GameConfig.DIFF_CAP)
            prev = l
            t += 0.05f
        }
        assertEquals(GameConfig.DIFF_CAP, Difficulty.level(1e6f), 0f)
    }

    @Test
    fun derivedQuantitiesAreMonotonic() {
        var prevGap = Float.MAX_VALUE
        var prevP2 = -1f
        var t = 0f
        while (t < 3600f) {
            val l = Difficulty.level(t)
            val g = Difficulty.gap(l)
            val p2 = Difficulty.doubleBlockChance(l)
            assertTrue(g <= prevGap)
            assertTrue(g >= GameConfig.GAP_HARD)
            assertTrue(p2 >= prevP2 && p2 <= 0.6f)
            prevGap = g; prevP2 = p2
            t += 0.1f
        }
    }

    @Test
    fun graceKindsOnly() {
        var r = 0f
        while (r < 1f) {
            val k = Difficulty.pickKind(0f, true, r)
            assertTrue(k == ObstacleKind.BARRIER || k == ObstacleKind.SLOW_CAR)
            r += 0.01f
        }
        // all four kinds reachable later
        val seen = BooleanArray(ObstacleKind.COUNT)
        r = 0f
        while (r < 1f) { seen[Difficulty.pickKind(0.9f, false, r)] = true; r += 0.005f }
        assertTrue(seen.all { it })
    }
}
