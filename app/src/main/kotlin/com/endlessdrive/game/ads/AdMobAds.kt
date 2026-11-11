package com.endlessdrive.game.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.endlessdrive.game.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * AdMob (interstitial + rewarded, no banners) behind the UMP consent flow.
 * Unit ids come from app/ads.properties through BuildConfig. Every SDK call is guarded: offline,
 * no fill or a blocked SDK just means "nothing ready", and failed loads retry with back-off.
 */
class AdMobAds(context: Context) : Ads {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var consent: ConsentInformation? = null
    private var initRequested = false
    private var ready = false

    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var loadingI = false
    private var loadingR = false
    private var failsI = 0
    private var failsR = 0

    override fun start(activity: Activity) {
        try {
            val info = UserMessagingPlatform.getConsentInformation(activity)
            consent = info
            val params = ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build()
            info.requestConsentInfoUpdate(
                activity, params,
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                        if (error != null) Log.i(TAG, "consent form: ${error.message}")
                        initIfAllowed()
                    }
                },
                { error ->
                    Log.i(TAG, "consent info unavailable: ${error.message}")
                    initIfAllowed()
                },
            )
            initIfAllowed() // consent remembered from an earlier launch
        } catch (t: Throwable) {
            Log.w(TAG, "consent flow unavailable, ads stay off", t)
        }
    }

    private fun initIfAllowed() {
        if (initRequested || consent?.canRequestAds() != true) return
        initRequested = true
        Thread {
            try {
                MobileAds.initialize(app) { main.post { ready = true; loadInterstitial(); loadRewarded() } }
            } catch (t: Throwable) {
                Log.w(TAG, "ad SDK init failed, ads stay off", t)
            }
        }.apply { isDaemon = true; name = "ads-init" }.start()
    }

    private fun retryDelay(fails: Int): Long = minOf(300_000L, 20_000L shl minOf(fails - 1, 4))

    private fun loadInterstitial() {
        if (!ready || loadingI || interstitial != null) return
        loadingI = true
        try {
            InterstitialAd.load(app, BuildConfig.AD_INTERSTITIAL, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { loadingI = false; failsI = 0; interstitial = ad }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    loadingI = false; failsI++
                    main.postDelayed({ loadInterstitial() }, retryDelay(failsI))
                }
            })
        } catch (t: Throwable) {
            loadingI = false
            Log.w(TAG, "interstitial load failed", t)
        }
    }

    private fun loadRewarded() {
        if (!ready || loadingR || rewarded != null) return
        loadingR = true
        try {
            RewardedAd.load(app, BuildConfig.AD_REWARDED, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { loadingR = false; failsR = 0; rewarded = ad }
                override fun onAdFailedToLoad(e: LoadAdError) {
                    loadingR = false; failsR++
                    main.postDelayed({ loadRewarded() }, retryDelay(failsR))
                }
            })
        } catch (t: Throwable) {
            loadingR = false
            Log.w(TAG, "rewarded load failed", t)
        }
    }

    override val interstitialReady: Boolean get() = interstitial != null
    override val rewardedReady: Boolean get() = rewarded != null

    override fun showInterstitial(activity: Activity, done: () -> Unit): Boolean {
        val ad = interstitial ?: return false
        interstitial = null
        var finished = false
        val finish = { if (!finished) { finished = true; done(); loadInterstitial() } }
        return try {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() = finish()
                override fun onAdFailedToShowFullScreenContent(e: AdError) = finish()
            }
            ad.show(activity)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "interstitial show failed", t)
            loadInterstitial()
            false
        }
    }

    override fun showRewarded(activity: Activity, done: (earned: Boolean) -> Unit): Boolean {
        val ad = rewarded ?: return false
        rewarded = null
        var earned = false
        var finished = false
        val finish = { ok: Boolean -> if (!finished) { finished = true; done(ok); loadRewarded() } }
        return try {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() = finish(earned)
                override fun onAdFailedToShowFullScreenContent(e: AdError) = finish(false)
            }
            ad.show(activity) { earned = true }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "rewarded show failed", t)
            loadRewarded()
            false
        }
    }

    override val privacyOptionsRequired: Boolean
        get() = consent?.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun showPrivacyOptions(activity: Activity, done: () -> Unit) {
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { done() }
        } catch (t: Throwable) {
            Log.w(TAG, "privacy options unavailable", t)
            done()
        }
    }

    private companion object {
        const val TAG = "Ads"
    }
}
