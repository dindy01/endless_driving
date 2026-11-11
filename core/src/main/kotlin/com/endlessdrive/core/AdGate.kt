package com.endlessdrive.core

/**
 * When ads may be shown. Pure policy with no SDK types, so it is unit-testable.
 *
 * Interstitial: only between runs (never during gameplay), never while the session has completed
 * fewer than [freeRuns] + 1 runs, at most one per [runsPerAd] completed runs, and at least
 * [minGapMs] after the previous one. Rewarded continue: at most once per run.
 * A "session" is one process lifetime; the caller owns one AdGate per process.
 */
class AdGate(
    private val runsPerAd: Int = 4,
    private val minGapMs: Long = 180_000L,
    private val freeRuns: Int = 3,
) {
    var completedRuns = 0
        private set
    var runsSinceAd = 0
        private set
    private var lastAdMs = 0L
    private var adShownOnce = false
    private var continued = false

    fun runStarted() {
        continued = false
    }

    fun runCompleted() {
        completedRuns++
        runsSinceAd++
    }

    fun interstitialAllowed(nowMs: Long, playing: Boolean): Boolean {
        if (playing) return false
        if (completedRuns <= freeRuns) return false
        if (runsSinceAd < runsPerAd) return false
        if (adShownOnce && nowMs - lastAdMs < minGapMs) return false
        return true
    }

    fun interstitialShown(nowMs: Long) {
        adShownOnce = true
        lastAdMs = nowMs
        runsSinceAd = 0
    }

    fun continueAvailable(): Boolean = !continued

    fun continueUsed() {
        continued = true
    }
}
