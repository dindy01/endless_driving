package com.endlessdrive.game

import android.app.Application
import com.endlessdrive.core.AdGate
import com.endlessdrive.game.ads.AdMobAds
import com.endlessdrive.game.ads.Ads

/** Process-wide state. The ad "session" is the process lifetime, so AdGate lives here, not in the activity. */
open class DriveApp : Application() {
    val adGate = AdGate()
    val ads: Ads by lazy { createAds() }

    protected open fun createAds(): Ads = AdMobAds(this)
}
