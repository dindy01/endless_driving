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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob + UMP implementation. Unit IDs come from app/ads.properties via BuildConfig.
 * Every SDK call is wrapped so failures (offline, no fill, ad blockers) never affect gameplay.
 */
class AdMobService(context: Context) : AdService {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val initStarted = AtomicBoolean(false)
    private var sdkReady = false
    private var consent: ConsentInformation? = null

    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var loadingInterstitial = false
    private var loadingRewarded = false
    private var interstitialFailures = 0
    private var rewardedFailures = 0

    override fun start(activity: Activity) {
        try {
            val info = UserMessagingPlatform.getConsentInformation(activity)
            consent = info
            val params = ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .build()
            info.requestConsentInfoUpdate(
                activity, params,
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { err ->
                        if (err != null) Log.w(TAG, "consent form: ${err.message}")
                        if (info.canRequestAds()) initSdk()
                    }
                },
                { err ->
                    Log.w(TAG, "consent update failed: ${err.message}")
                    if (info.canRequestAds()) initSdk()
                },
            )
            // Consent from a previous session lets us start loading immediately.
            if (info.canRequestAds()) initSdk()
        } catch (e: Throwable) {
            Log.w(TAG, "UMP unavailable; ads disabled", e)
        }
    }

    private fun initSdk() {
        if (!initStarted.compareAndSet(false, true)) return
        Thread {
            try {
                MobileAds.initialize(app) {
                    main.post {
                        sdkReady = true
                        loadInterstitial()
                        loadRewarded()
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "MobileAds init failed; ads disabled", e)
            }
        }.apply { name = "ads-init"; isDaemon = true }.start()
    }

    private fun backoffMs(failures: Int): Long = (15_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(300_000L)

    private fun loadInterstitial() {
        if (!sdkReady || loadingInterstitial || interstitial != null) return
        loadingInterstitial = true
        try {
            InterstitialAd.load(
                app, BuildConfig.AD_INTERSTITIAL_ID, AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        loadingInterstitial = false
                        interstitialFailures = 0
                        interstitial = ad
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loadingInterstitial = false
                        interstitialFailures++
                        Log.i(TAG, "interstitial no fill/offline: ${error.code}")
                        main.postDelayed({ loadInterstitial() }, backoffMs(interstitialFailures))
                    }
                },
            )
        } catch (e: Throwable) {
            loadingInterstitial = false
            Log.w(TAG, "interstitial load threw", e)
        }
    }

    private fun loadRewarded() {
        if (!sdkReady || loadingRewarded || rewarded != null) return
        loadingRewarded = true
        try {
            RewardedAd.load(
                app, BuildConfig.AD_REWARDED_ID, AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        loadingRewarded = false
                        rewardedFailures = 0
                        rewarded = ad
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loadingRewarded = false
                        rewardedFailures++
                        Log.i(TAG, "rewarded no fill/offline: ${error.code}")
                        main.postDelayed({ loadRewarded() }, backoffMs(rewardedFailures))
                    }
                },
            )
        } catch (e: Throwable) {
            loadingRewarded = false
            Log.w(TAG, "rewarded load threw", e)
        }
    }

    override fun isInterstitialReady() = interstitial != null
    override fun isRewardedReady() = rewarded != null

    override fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val ad = interstitial ?: return false
        interstitial = null
        return try {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onClosed()
                    loadInterstitial()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    onClosed()
                    loadInterstitial()
                }
            }
            ad.show(activity)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "interstitial show threw", e)
            loadInterstitial()
            false
        }
    }

    override fun showRewarded(activity: Activity, onClosed: (earned: Boolean) -> Unit): Boolean {
        val ad = rewarded ?: return false
        rewarded = null
        var earned = false
        return try {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onClosed(earned)
                    loadRewarded()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    onClosed(false)
                    loadRewarded()
                }
            }
            ad.show(activity) { earned = true }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "rewarded show threw", e)
            loadRewarded()
            false
        }
    }

    override fun isPrivacyOptionsRequired(): Boolean =
        consent?.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun showPrivacyOptions(activity: Activity, onDone: () -> Unit) {
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { onDone() }
        } catch (e: Throwable) {
            Log.w(TAG, "privacy options failed", e)
            onDone()
        }
    }

    private companion object {
        const val TAG = "Ads"
    }
}
