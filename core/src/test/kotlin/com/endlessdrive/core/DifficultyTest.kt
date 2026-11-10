package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyTest {
    @Test
    fun speedMonotonicAndCapped() {
        var prev = Difficulty.baseSpeed(0f)
        assertEquals(GameConfig.V0, prev, 1e-4f)
        var t = 0f
        while (t < 7200f) {
            val v = Difficulty.baseSpeed(t)
            assertTrue(v >= prev && v <= GameConfig.V1)
            prev = v; t += 0.05f
        }
        assertEquals(GameConfig.V1, Difficulty.baseSpeed(1e6f), 1e-3f)
    }

    @Test
    fun levelZeroInGraceThenMonotonicCapped() {
        var t = 0f
        while (t <= GameConfig.GRACE_TIME) { assertEquals(0f, Difficulty.level(t), 0f); t += 0.05f }
        var prev = 0f
        t = 0f
        while (t < 7200f) {
            val l = Difficulty.level(t)
            assertTrue(l >= prev && l <= GameConfig.DIFF_CAP)
            prev = l; t += 0.05f
        }
        assertEquals(GameConfig.DIFF_CAP, Difficulty.level(1e6f), 0f)
    }

    @Test
    fun densityMonotonicAndCapped() {
        var prev = Float.MAX_VALUE
        var t = 0f
        while (t < 7200f) {
            val sp = Difficulty.spacing(t)
            assertTrue("spacing must not grow (density must not drop) at t=$t", sp <= prev)
            assertTrue(sp >= GameConfig.SPACING_HARD)
            prev = sp; t += 0.1f
        }
        assertTrue(Difficulty.spacing(0f) > Difficulty.spacing(GameConfig.GRACE_TIME + 1f))
    }

    @Test
    fun graceKindsAndAllKindsLater() {
        var r = 0f
        while (r < 1f) {
            val k = Difficulty.pickKind(0f, true, r)
            assertTrue(k == Kind.CAR || k == Kind.TRUCK)
            r += 0.01f
        }
        val seen = BooleanArray(Kind.COUNT)
        r = 0f
        while (r < 1f) { seen[Difficulty.pickKind(0.9f, false, r)] = true; r += 0.002f }
        for (k in 0 until Kind.ROADWORKS + 1) assertTrue("kind $k never chosen", seen[k])
    }
}
