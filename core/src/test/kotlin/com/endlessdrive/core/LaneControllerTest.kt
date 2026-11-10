package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneControllerTest {
    private val dt = 1f / 240f

    @Test
    fun laneChangeTakesAbout120ms() {
        val c = LaneController(); c.reset(1)
        c.request(+1, 0f)
        var t = 0f
        c.update(dt, t)
        while (c.isChanging) { t += dt; c.update(dt, t) }
        assertEquals(GameConfig.laneX(2), c.x, 1e-5f)
        assertEquals(0.12f, t + dt, 0.01f)
    }

    @Test
    fun rapidTapsAreBufferedNotDropped() {
        val c = LaneController(); c.reset(1)
        // two taps 20 ms apart: should end two lanes over (clamped at 0 after one more)
        c.request(-1, 0f)
        c.request(+1, 0.02f)
        c.request(+1, 0.03f)
        var t = 0f
        while (t < 0.6f) { c.update(dt, t); t += dt }
        assertEquals(2, c.lane)
        assertEquals(GameConfig.laneX(2), c.x, 1e-5f)
        assertEquals(0, c.pendingInputs)
    }

    @Test
    fun staleInputsExpire() {
        val c = LaneController(); c.reset(1)
        c.lock(1f)
        c.request(+1, 0f)
        var t = 0f
        while (t < 1.2f) { c.update(dt, t); t += dt }
        assertEquals("input older than TTL should be dropped", 1, c.lane)
    }

    @Test
    fun inputDuringShortLockIsAppliedAfter() {
        val c = LaneController(); c.reset(1)
        c.lock(0.2f)
        c.request(-1, 0f)
        var t = 0f
        while (t < 0.5f) { c.update(dt, t); t += dt }
        assertEquals(0, c.lane)
    }

    @Test
    fun clampsAtEdges() {
        val c = LaneController(); c.reset(0)
        c.request(-1, 0f)
        c.update(dt, 0f)
        assertEquals(0, c.lane)
        assertTrue(!c.isChanging)
    }
}
