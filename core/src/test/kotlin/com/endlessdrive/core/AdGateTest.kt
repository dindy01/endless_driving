package com.endlessdrive.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdGateTest {
    private val min = 60_000L

    @Test
    fun neverInFirstThreeRuns() {
        val g = AdGate()
        repeat(3) {
            g.onRunCompleted()
            assertFalse(g.shouldShowInterstitial(1_000_000L, inGameplay = false))
        }
        g.onRunCompleted()
        assertTrue(g.shouldShowInterstitial(1_000_000L, inGameplay = false))
    }

    @Test
    fun neverDuringGameplay() {
        val g = AdGate()
        repeat(10) { g.onRunCompleted() }
        assertFalse(g.shouldShowInterstitial(10 * min, inGameplay = true))
        assertTrue(g.shouldShowInterstitial(10 * min, inGameplay = false))
    }

    @Test
    fun atMostOncePerFourRunsAndThreeMinutes() {
        val g = AdGate()
        var now = 0L
        repeat(4) { g.onRunCompleted() }
        assertTrue(g.shouldShowInterstitial(now, false))
        g.onInterstitialShown(now)
        // 4 more runs but only 2 minutes later: blocked by time
        repeat(4) { g.onRunCompleted() }
        now += 2 * min
        assertFalse(g.shouldShowInterstitial(now, false))
        now += min // 3 minutes
        assertTrue(g.shouldShowInterstitial(now, false))
        g.onInterstitialShown(now)
        // 10 minutes later but only 3 runs: blocked by run count
        now += 10 * min
        repeat(3) { g.onRunCompleted() }
        assertFalse(g.shouldShowInterstitial(now, false))
        g.onRunCompleted()
        assertTrue(g.shouldShowInterstitial(now, false))
    }

    @Test
    fun simulatedSessionNeverExceedsLimits() {
        val g = AdGate()
        val rng = Rng(9)
        var now = 0L
        var lastShown = Long.MIN_VALUE
        var runsSince = 0
        var shown = 0
        for (run in 1..2000) {
            now += (5_000 + rng.nextInt(120_000)).toLong()
            g.onRunCompleted(); runsSince++
            if (g.shouldShowInterstitial(now, false)) {
                assertTrue(run > 3)
                assertTrue(runsSince >= 4)
                assertTrue(lastShown == Long.MIN_VALUE || now - lastShown >= 3 * min)
                g.onInterstitialShown(now); lastShown = now; runsSince = 0; shown++
            }
        }
        assertTrue(shown > 0)
    }

    @Test
    fun continueOncePerRun() {
        val g = AdGate()
        g.onRunStarted()
        assertTrue(g.canOfferContinue())
        g.onContinueUsed()
        assertFalse(g.canOfferContinue())
        g.onRunStarted()
        assertTrue(g.canOfferContinue())
    }
}
