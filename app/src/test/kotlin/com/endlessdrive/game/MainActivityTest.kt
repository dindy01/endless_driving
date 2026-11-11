package com.endlessdrive.game

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.endlessdrive.core.Kind
import com.endlessdrive.core.World
import com.endlessdrive.game.MainActivity.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, qualifiers = "w800dp-h360dp-land-xhdpi")
class MainActivityTest {
    private lateinit var ctl: ActivityController<MainActivity>
    private lateinit var a: MainActivity
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val ads get() = app.ads as FakeAds

    @Before
    fun setUp() {
        GameSurface.glEnabled = false
        Audio.threadEnabled = false
        app.getSharedPreferences(Settings.FILE, 0).edit().clear().commit()
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        a = ctl.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun tap(id: Int) { a.findViewById<View>(id).performClick(); idle() }
    private fun shown(id: Int) = a.findViewById<View>(id).visibility == View.VISIBLE
    private val world get() = a.controller.world

    /** Drives the current run into a barrier and waits for the game-over screen. */
    private fun crash() {
        assertEquals(Screen.PLAYING, a.screen)
        a.stepFrames(200) // also outlasts post-continue invulnerability
        world.traffic.spawningEnabled = false
        world.traffic.clearAll()
        world.traffic.placeForTest(Kind.BARRIER, if (world.x < 0f) 0 else 1, world.s + 25.0)
        a.stepFrames(300)
        idle()
        assertEquals(World.OVER, world.state)
        assertEquals(Screen.GAME_OVER, a.screen)
    }

    private fun playRuns(n: Int, viaRetry: Boolean = true) {
        repeat(n) {
            crash()
            tap(if (viaRetry) R.id.btnRetry else R.id.btnOverMenu)
            if (!viaRetry) tap(R.id.btnPlay)
        }
    }

    @Test
    fun startsAtMenuWithAttractBackdropAndConsentFlow() {
        assertEquals(Screen.MENU, a.screen)
        assertTrue(shown(R.id.panelMenu))
        assertTrue("UMP/consent flow started", ads.started)
        a.stepFrames(5)
        assertEquals(World.ATTRACT, world.state)
        assertTrue(a.findViewById<TextView>(R.id.txtMenuBest).text.contains("0"))
    }

    @Test
    fun playPauseResumeAndBack() {
        tap(R.id.btnPlay)
        assertEquals(Screen.PLAYING, a.screen)
        a.stepFrames(120)
        assertEquals(World.RUNNING, world.state)
        assertTrue(world.distance > 30f)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(Screen.PAUSED, a.screen)
        assertTrue(shown(R.id.panelPause))
        val d = world.distance
        a.stepFrames(120)
        assertEquals("frozen while paused", d, world.distance, 0f)
        tap(R.id.btnResume)
        assertEquals(Screen.PLAYING, a.screen)
        a.stepFrames(30)
        assertTrue(world.distance > d)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        tap(R.id.btnQuit)
        assertEquals(Screen.MENU, a.screen)
        assertEquals(1, a.settings.totalRuns)
    }

    @Test
    fun crashShowsGameOverAndPersistsBest() {
        tap(R.id.btnPlay)
        crash()
        val score = world.score
        assertTrue(score > 0)
        assertEquals("%,d".format(score), a.findViewById<TextView>(R.id.txtScore).text.toString())
        assertTrue(shown(R.id.txtNewBest))
        assertEquals(score, a.settings.best)
        // a brand new activity (process death) reads it back
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(Screen.MENU, b.screen)
        assertTrue(b.findViewById<TextView>(R.id.txtMenuBest).text.contains("%,d".format(score)))
    }

    @Test
    fun retryStartsAFreshRunImmediately() {
        tap(R.id.btnPlay)
        crash()
        val t0 = System.nanoTime()
        tap(R.id.btnRetry)
        a.stepFrames(1)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(Screen.PLAYING, a.screen)
        assertEquals(World.RUNNING, world.state)
        assertTrue(world.distance < 2f)
        assertEquals(0, world.coins)
        assertTrue("retry took $ms ms", ms < 1000)
        println("retry (UI + world reset, no GPU): $ms ms")
    }

    @Test
    fun rewardedContinueIsOfferedOncePerRun() {
        tap(R.id.btnPlay)
        crash()
        assertTrue(shown(R.id.btnContinue))
        val score = world.score
        tap(R.id.btnContinue)
        assertEquals(1, ads.rewardeds)
        assertEquals(Screen.PLAYING, a.screen)
        a.stepFrames(2)
        assertEquals(World.RUNNING, world.state)
        assertTrue("score kept", world.score >= score)
        crash()
        assertFalse("only once per run", shown(R.id.btnContinue))
        tap(R.id.btnRetry)
        crash()
        assertTrue("offered again on the next run", shown(R.id.btnContinue))
    }

    @Test
    fun continueNeedsTheRewardAndALoadedAd() {
        ads.grantReward = false
        tap(R.id.btnPlay)
        crash()
        tap(R.id.btnContinue)
        assertEquals("ad closed early: still game over", Screen.GAME_OVER, a.screen)
        tap(R.id.btnRetry)
        ads.hasRewarded = false
        crash()
        assertFalse("nothing loaded (offline): no button", shown(R.id.btnContinue))
    }

    @Test
    fun interstitialCadence() {
        tap(R.id.btnPlay)
        playRuns(3)
        assertEquals("never in the first 3 runs", 0, ads.interstitials)
        playRuns(1)
        assertEquals("after the 4th completed run", 1, ads.interstitials)
        assertEquals("game resumes after the ad", Screen.PLAYING, a.screen)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        playRuns(3)
        assertEquals("at most once per 4 runs", 1, ads.interstitials)
        playRuns(1)
        assertEquals(2, ads.interstitials)
        // four more quick runs: the 3-minute rule blocks it
        playRuns(4)
        assertEquals("at least 3 minutes apart", 2, ads.interstitials)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        playRuns(1)
        assertEquals(3, ads.interstitials)
        assertTrue(SystemClock.elapsedRealtime() > 0)
    }

    @Test
    fun fullyPlayableWithoutAnyAds() {
        ads.hasInterstitial = false
        ads.hasRewarded = false
        tap(R.id.btnPlay)
        playRuns(9)
        assertEquals(0, ads.interstitials)
        assertEquals(Screen.PLAYING, a.screen)
        assertEquals(9, a.settings.totalRuns)
    }

    @Test
    fun backgroundingPausesAndRecreationReturnsToMenu() {
        tap(R.id.btnPlay)
        a.stepFrames(60)
        ctl.pause() // screen off / home
        assertEquals(Screen.PAUSED, a.screen)
        ctl.stop()
        ctl.start().resume()
        assertEquals(Screen.PAUSED, a.screen)
        tap(R.id.btnResume)
        a.stepFrames(10)
        assertEquals(World.RUNNING, world.state)
        // losing window focus (notification shade) also pauses
        a.onWindowFocusChanged(false)
        assertEquals(Screen.PAUSED, a.screen)
        // destroy + recreate
        ctl.pause().stop().destroy()
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(Screen.MENU, b.screen)
        b.stepFrames(5)
    }

    @Test
    fun gameOverWhileBackgroundedIsNotLost() {
        tap(R.id.btnPlay)
        a.stepFrames(200)
        world.traffic.spawningEnabled = false
        world.traffic.clearAll()
        world.traffic.placeForTest(Kind.BARRIER, if (world.x < 0f) 0 else 1, world.s + 25.0)
        a.stepFrames(60) // mid crash sequence
        assertEquals(World.CRASHING, world.state)
        ctl.pause()
        ctl.resume()
        tap(R.id.btnResume)
        a.stepFrames(200); idle()
        assertEquals(Screen.GAME_OVER, a.screen)
    }

    @Test
    fun settingsPersist() {
        tap(R.id.btnSettings)
        assertEquals(Screen.SETTINGS, a.screen)
        assertEquals("touch steering is the default", false, a.settings.tilt)
        assertEquals("Steering: Touch", a.findViewById<Button>(R.id.btnControl).text.toString())
        tap(R.id.btnSound); tap(R.id.btnVibration); tap(R.id.btnQuality)
        assertFalse(a.settings.sound); assertFalse(a.settings.vibration)
        assertEquals(2, a.settings.quality)
        assertEquals("Graphics: High", a.findViewById<Button>(R.id.btnQuality).text.toString())
        tap(R.id.btnQuality); tap(R.id.btnQuality)
        assertEquals(1, a.settings.quality)
        assertTrue(shown(R.id.btnPrivacy))
        val s2 = Settings(app)
        assertFalse(s2.sound); assertFalse(s2.vibration)
        tap(R.id.btnBack)
        assertEquals(Screen.MENU, a.screen)
    }

    private fun touch(hud: HudView, action: Int, x: Float, y: Float, t0: Long) {
        val e = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
        hud.dispatchTouchEvent(e)
        e.recycle()
    }

    @Test
    fun touchControls() {
        tap(R.id.btnPlay)
        val hud = a.findViewById<HudView>(R.id.hud)
        val w = hud.width.toFloat(); val h = hud.height.toFloat()
        val dpi = a.resources.displayMetrics.density
        val t0 = SystemClock.uptimeMillis()
        // analog steering: drag distance maps to steering amount
        touch(hud, MotionEvent.ACTION_DOWN, w * 0.2f, h * 0.7f, t0)
        assertEquals(0f, a.controller.steer, 0f)
        touch(hud, MotionEvent.ACTION_MOVE, w * 0.2f + 31f * dpi, h * 0.7f, t0)
        val half = a.controller.steer
        assertTrue("partial lock: $half", half > 0.2f && half < 0.6f)
        touch(hud, MotionEvent.ACTION_MOVE, w * 0.2f + 200f * dpi, h * 0.7f, t0)
        assertEquals(1f, a.controller.steer, 1e-3f)
        touch(hud, MotionEvent.ACTION_MOVE, w * 0.2f + 200f * dpi - 124f * dpi, h * 0.7f, t0)
        assertEquals("reversing from past full lock is immediate", -1f, a.controller.steer, 1e-3f)
        touch(hud, MotionEvent.ACTION_UP, w * 0.2f, h * 0.7f, t0)
        assertEquals(0f, a.controller.steer, 0f)
        // the car really moves sideways
        val x0 = world.x
        touch(hud, MotionEvent.ACTION_DOWN, w * 0.3f, h * 0.7f, t0)
        touch(hud, MotionEvent.ACTION_MOVE, w * 0.3f - 100f * dpi, h * 0.7f, t0)
        a.stepFrames(20)
        assertTrue(world.x < x0 - 0.5f)
        touch(hud, MotionEvent.ACTION_UP, w * 0.3f, h * 0.7f, t0)
        // brake and nitro buttons
        touch(hud, MotionEvent.ACTION_DOWN, w - 182f * dpi, h - 58f * dpi, t0)
        assertTrue(a.controller.brake)
        touch(hud, MotionEvent.ACTION_UP, w - 182f * dpi, h - 58f * dpi, t0)
        assertFalse(a.controller.brake)
        touch(hud, MotionEvent.ACTION_DOWN, w - 70f * dpi, h - 84f * dpi, t0)
        assertTrue(a.controller.nitro)
        a.stepFrames(5)
        assertTrue(world.boosting)
        touch(hud, MotionEvent.ACTION_UP, w - 70f * dpi, h - 84f * dpi, t0)
        // pause button
        touch(hud, MotionEvent.ACTION_DOWN, w - 34f * dpi, 34f * dpi, t0); idle()
        assertEquals(Screen.PAUSED, a.screen)
        assertFalse("inputs released when the HUD goes inactive", a.controller.nitro || a.controller.brake)
    }

    @Test
    fun tiltMappingAndShaping() {
        val t = TiltInput(app, { Surface.ROTATION_90 }) {}
        var s = 0f
        repeat(40) { s = t.steerFrom(0f, 9.81f * 0.375f, Surface.ROTATION_90) }
        assertEquals("22 degrees of roll is full lock", 1f, s, 0.02f)
        val u = TiltInput(app, { Surface.ROTATION_270 }) {}
        repeat(40) { s = u.steerFrom(0f, 9.81f * 0.375f, Surface.ROTATION_270) }
        assertEquals("mirrored when the phone is flipped", -1f, s, 0.02f)
        val v = TiltInput(app, { Surface.ROTATION_90 }) {}
        repeat(40) { s = v.steerFrom(0f, 0.1f, Surface.ROTATION_90) }
        assertEquals("dead zone", 0f, s, 0f)
        assertEquals(0.375f, HudView.shape(0.5f), 1e-4f)
        assertEquals(-1f, HudView.shape(-3f), 0f)
    }
}
