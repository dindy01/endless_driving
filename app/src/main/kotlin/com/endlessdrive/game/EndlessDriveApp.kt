package com.endlessdrive.game

import android.app.Application
import com.endlessdrive.core.AdGate
import com.endlessdrive.game.ads.AdMobService
import com.endlessdrive.game.ads.AdService

/**
 * Process-scoped objects. AdGate lives here so the "session" (= process lifetime) survives
 * activity recreation; after process death a new session starts, as intended.
 */
open class EndlessDriveApp : Application() {
    val adGate = AdGate()
    val ads: AdService by lazy { createAds() }

    protected open fun createAds(): AdService = AdMobService(this)
}
