package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdGateTest {
    private val min = 60_000L

    @Test
    fun neverDuringGameplay() {
        val g = AdGate()
        repeat(20) { g.runStarted(); g.runCompleted() }
        assertTrue(g.interstitialAllowed(100 * min, playing = false))
        assertFalse(g.interstitialAllowed(100 * min, playing = true))
    }

    @Test
    fun neverInTheFirstThreeRunsOfASession() {
        val g = AdGate()
        for (run in 1..3) {
            g.runStarted(); g.runCompleted()
            assertFalse("after run $run", g.interstitialAllowed(run * 60 * min, false))
        }
        g.runStarted(); g.runCompleted()
        assertTrue("after the 4th run", g.interstitialAllowed(1000 * min, false))
    }

    @Test
    fun atMostOncePerFourRunsAndThreeMinutes() {
        val g = AdGate()
        var now = 0L
        repeat(4) { g.runCompleted() }
        assertTrue(g.interstitialAllowed(now, false))
        g.interstitialShown(now)
        // three more runs, plenty of time: still blocked by the run count
        repeat(3) { g.runCompleted(); now += 10 * min; assertFalse(g.interstitialAllowed(now, false)) }
        g.runCompleted()
        assertTrue(g.interstitialAllowed(now, false))
        g.interstitialShown(now)
        // four quick runs: blocked by the 3 minute gap
        repeat(4) { g.runCompleted(); now += 20_000 }
        assertFalse(g.interstitialAllowed(now, false))
        assertFalse(g.interstitialAllowed(now + 3 * min - 80_000 - 1, false))
        assertTrue(g.interstitialAllowed(now + 3 * min - 80_000, false))
    }

    @Test
    fun simulatedSessionRespectsEveryRule() {
        val rng = Rng(3)
        val g = AdGate()
        var now = 0L
        var lastAd = -1L
        var runsAtLastAd = 0
        var shown = 0
        for (run in 1..2000) {
            g.runStarted()
            assertFalse(g.interstitialAllowed(now, playing = true))
            now += (5_000 + rng.nextInt(120_000)).toLong()
            g.runCompleted()
            if (g.interstitialAllowed(now, false)) {
                assertTrue(run > 3)
                assertTrue(run - runsAtLastAd >= 4)
                if (lastAd >= 0) assertTrue(now - lastAd >= 3 * min)
                g.interstitialShown(now)
                lastAd = now; runsAtLastAd = run; shown++
            }
        }
        assertTrue(shown in 100..500)
    }

    @Test
    fun adNotLoadedDoesNotConsumeTheSlot() {
        val g = AdGate()
        repeat(4) { g.runCompleted() }
        assertTrue(g.interstitialAllowed(0, false))
        // nothing shown (offline): still allowed after the next run
        g.runCompleted()
        assertTrue(g.interstitialAllowed(1, false))
        assertEquals(5, g.runsSinceAd)
    }

    @Test
    fun continueOncePerRun() {
        val g = AdGate()
        g.runStarted()
        assertTrue(g.continueAvailable())
        g.continueUsed()
        assertFalse(g.continueAvailable())
        g.runStarted()
        assertTrue(g.continueAvailable())
    }
}
