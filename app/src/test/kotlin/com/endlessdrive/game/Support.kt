package com.endlessdrive.game

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.endlessdrive.game.ads.Ads

/** Scriptable ad provider: records what was shown and closes ads on the next main-loop turn. */
class FakeAds : Ads {
    var started = false
    var hasInterstitial = true
    var hasRewarded = true
    var grantReward = true
    var interstitials = 0
    var rewardeds = 0
    private val main = Handler(Looper.getMainLooper())

    override fun start(activity: Activity) { started = true }
    override val interstitialReady get() = hasInterstitial
    override val rewardedReady get() = hasRewarded

    override fun showInterstitial(activity: Activity, done: () -> Unit): Boolean {
        if (!hasInterstitial) return false
        interstitials++
        main.post { done() }
        return true
    }

    override fun showRewarded(activity: Activity, done: (earned: Boolean) -> Unit): Boolean {
        if (!hasRewarded) return false
        rewardeds++
        main.post { done(grantReward) }
        return true
    }

    override val privacyOptionsRequired = false
    override fun showPrivacyOptions(activity: Activity, done: () -> Unit) = done()
}

class TestApp : DriveApp() {
    override fun createAds(): Ads = FakeAds()
}
