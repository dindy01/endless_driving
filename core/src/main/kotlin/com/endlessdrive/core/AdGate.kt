package com.endlessdrive.core

/**
 * Pure ad-frequency policy (no SDK types):
 *  - interstitial: never during gameplay, never in the first [graceRuns] completed runs of a
 *    session, at most once per [runsBetween] completed runs AND at least [minIntervalMs] since the
 *    previous one.
 *  - rewarded "continue": at most once per run.
 */
class AdGate(
    private val runsBetween: Int = 4,
    private val minIntervalMs: Long = 3 * 60 * 1000L,
    private val graceRuns: Int = 3,
) {
    var sessionRuns = 0
        private set
    var runsSinceInterstitial = 0
        private set
    var lastInterstitialAtMs = NEVER
        private set
    var continueUsedThisRun = false
        private set

    fun onRunStarted() {
        continueUsedThisRun = false
    }

    fun onRunCompleted() {
        sessionRuns++
        runsSinceInterstitial++
    }

    fun shouldShowInterstitial(nowMs: Long, inGameplay: Boolean): Boolean {
        if (inGameplay) return false
        if (sessionRuns <= graceRuns) return false
        if (runsSinceInterstitial < runsBetween) return false
        if (lastInterstitialAtMs != NEVER && nowMs - lastInterstitialAtMs < minIntervalMs) return false
        return true
    }

    fun onInterstitialShown(nowMs: Long) {
        lastInterstitialAtMs = nowMs
        runsSinceInterstitial = 0
    }

    fun canOfferContinue(): Boolean = !continueUsedThisRun

    fun onContinueUsed() {
        continueUsedThisRun = true
    }

    companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
