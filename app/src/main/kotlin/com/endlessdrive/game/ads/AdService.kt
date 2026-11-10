package com.endlessdrive.game.ads

import android.app.Activity

/**
 * Platform ad abstraction. *Policy* (when an ad may be shown) lives in core's AdGate;
 * implementations only load/show and must fail silently (offline, no fill, blocked).
 */
interface AdService {
    /** Runs the consent flow (UMP) and, if permitted, initialises and preloads ads. */
    fun start(activity: Activity)

    fun isInterstitialReady(): Boolean
    fun isRewardedReady(): Boolean

    /** Shows an interstitial if one is loaded. [onClosed] is always called iff this returns true. */
    fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean

    /** Shows a rewarded ad if loaded. [onClosed] receives whether the reward was earned. */
    fun showRewarded(activity: Activity, onClosed: (earned: Boolean) -> Unit): Boolean

    fun isPrivacyOptionsRequired(): Boolean
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit)
}

/** Used when ads are unavailable; the game is fully playable without them. */
object NoAdService : AdService {
    override fun start(activity: Activity) {}
    override fun isInterstitialReady() = false
    override fun isRewardedReady() = false
    override fun showInterstitial(activity: Activity, onClosed: () -> Unit) = false
    override fun showRewarded(activity: Activity, onClosed: (earned: Boolean) -> Unit) = false
    override fun isPrivacyOptionsRequired() = false
    override fun showPrivacyOptions(activity: Activity, onDone: () -> Unit) = onDone()
}
