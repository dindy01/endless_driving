package com.endlessdrive.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TiltAndHudTest {
    @Test
    fun tiltMapping() {
        assertEquals(0f, TiltSteering.steerFor(0f, 9.8f), 1e-4f) // level
        // rotating the phone clockwise (steer right) tilts "up" toward screen-left (sx < 0)
        assertTrue(TiltSteering.steerFor(-2f, 9.6f) > 0.2f)
        assertTrue(TiltSteering.steerFor(2f, 9.6f) < -0.2f)
        assertEquals(1f, TiltSteering.steerFor(-9f, 5f), 0f) // clamped
        assertEquals(0f, TiltSteering.steerFor(-0.3f, 9.8f), 0f) // dead zone
    }

    @Test
    fun numberFormatting() {
        val b = CharArray(24)
        assertEquals("12,345", String(b, 0, HudView.formatNumber(12345, b, true)))
        assertEquals("0", String(b, 0, HudView.formatNumber(0, b, false)))
        b[0] = 'x'
        assertEquals("x3", String(b, 0, HudView.formatNumber(3, b, false, 1)))
        assertEquals("SHIELD 4.2s", String(b, 0, HudView.formatTenths(4.25f, b, "SHIELD ")))
    }
}
