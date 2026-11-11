package com.endlessdrive.game.ads

import android.app.Activity

/**
 * What the game needs from an ad SDK. Implementations only load and show; *when* an ad may appear
 * is decided by core's AdGate. Every method must be safe to call offline or with ads blocked.
 */
interface Ads {
    /** Consent flow first (UMP), then SDK init and preloading if ads may be requested. */
    fun start(activity: Activity)

    val interstitialReady: Boolean
    val rewardedReady: Boolean

    /** Returns false if nothing could be shown; otherwise [done] is called exactly once when it closes. */
    fun showInterstitial(activity: Activity, done: () -> Unit): Boolean

    /** Returns false if nothing could be shown; otherwise [done] reports whether the reward was earned. */
    fun showRewarded(activity: Activity, done: (earned: Boolean) -> Unit): Boolean

    val privacyOptionsRequired: Boolean
    fun showPrivacyOptions(activity: Activity, done: () -> Unit)
}

/** No ads at all. The game is fully playable with this. */
object NoAds : Ads {
    override fun start(activity: Activity) {}
    override val interstitialReady = false
    override val rewardedReady = false
    override fun showInterstitial(activity: Activity, done: () -> Unit) = false
    override fun showRewarded(activity: Activity, done: (earned: Boolean) -> Unit) = false
    override val privacyOptionsRequired = false
    override fun showPrivacyOptions(activity: Activity, done: () -> Unit) = done()
}
