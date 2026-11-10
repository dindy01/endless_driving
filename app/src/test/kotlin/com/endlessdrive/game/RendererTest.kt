package com.endlessdrive.game

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererTest {
    private fun fmt(v: Long, g: Boolean): String { val b = CharArray(24); return String(b, 0, Renderer.formatNumber(v, b, g)) }

    @Test
    fun numberFormatting() {
        assertEquals("0", fmt(0, true))
        assertEquals("999", fmt(999, true))
        assertEquals("1,000", fmt(1000, true))
        assertEquals("1234567", fmt(1234567, false))
        assertEquals("12,345,678", fmt(12345678, true))
        val b = CharArray(24)
        assertEquals("4.2s", String(b, 0, Renderer.formatTenths(4.25f, b)))
    }
}
