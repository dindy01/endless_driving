package com.endlessdrive.game

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.endlessdrive.game.ads.AdService

class FakeAds : AdService {
    var interstitialReady = true
    var rewardedReady = true
    var earnReward = true
    var interstitialShows = 0
    var rewardedShows = 0
    var started = false
    private val main = Handler(Looper.getMainLooper())

    override fun start(activity: Activity) { started = true }
    override fun isInterstitialReady() = interstitialReady
    override fun isRewardedReady() = rewardedReady

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        if (!interstitialReady) return false
        interstitialShows++
        main.post { onClosed() }
        return true
    }

    override fun showRewarded(activity: Activity, onClosed: (earned: Boolean) -> Unit): Boolean {
        if (!rewardedReady) return false
        rewardedShows++
        main.post { onClosed(earnReward) }
        return true
    }

    override fun isPrivacyOptionsRequired() = false
    override fun showPrivacyOptions(activity: Activity, onDone: () -> Unit) = onDone()
}

class TestApp : EndlessDriveApp() {
    override fun createAds(): AdService = FakeAds()
}
