package com.endlessdrive.game

import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.endlessdrive.core.ObstacleKind
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp-port-xxhdpi")
class MainActivityTest {

    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val ads get() = app.ads as FakeAds

    @Before
    fun setUp() {
        GameView.loopEnabled = false
        app.getSharedPreferences(Prefs.FILE, 0).edit().clear().commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun click(id: Int) { activity.findViewById<View>(id).performClick(); idle() }
    private fun visible(id: Int) = activity.findViewById<View>(id).visibility == View.VISIBLE
    private fun frames(n: Int) = repeat(n) { activity.gameView.advance(1f / 60f) }

    private fun crash() {
        val w = activity.gameView.world
        assertEquals(Screen.PLAYING, activity.screen)
        w.disableSpawningForTest()
        for (o in w.obstacles) o.active = false
        frames(130) // outlast any post-continue invulnerability
        w.placeObstacleForTest(ObstacleKind.BARRIER, w.lane.lane, w.distance + 1.5f)
        frames(30)
        assertEquals(Screen.CRASHED, activity.screen)
        frames(60) // crash sequence (0.9 s)
        idle()
        assertEquals(Screen.GAME_OVER, activity.screen)
    }

    @Test
    fun startsAtMenuAndRunsConsent() {
        assertEquals(Screen.MENU, activity.screen)
        assertTrue(visible(R.id.panelMenu))
        assertTrue(ads.started)
    }

    @Test
    fun playPauseResume() {
        click(R.id.btnPlay)
        assertEquals(Screen.PLAYING, activity.screen)
        assertTrue(visible(R.id.btnPause))
        frames(120)
        assertTrue(activity.gameView.world.distance > 5f)
        click(R.id.btnPause)
        assertEquals(Screen.PAUSED, activity.screen)
        val d = activity.gameView.world.distance
        frames(120)
        assertEquals("world frozen while paused", d, activity.gameView.world.distance, 0f)
        click(R.id.btnResume)
        assertEquals(Screen.PLAYING, activity.screen)
        frames(10)
        assertTrue(activity.gameView.world.distance > d)
    }

    @Test
    fun tapsChangeLanes() {
        click(R.id.btnPlay)
        val gv = activity.gameView
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 10f, 500f, 0)
        val up = android.view.MotionEvent.obtain(0, 50, android.view.MotionEvent.ACTION_UP, 10f, 500f, 0)
        gv.dispatchTouchEvent(down); gv.dispatchTouchEvent(up)
        frames(20)
        assertEquals(0, gv.world.lane.lane)
        // swipe right across the left half: one lane right, not a "left tap"
        val d2 = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 50f, 500f, 0)
        val m2 = android.view.MotionEvent.obtain(0, 30, android.view.MotionEvent.ACTION_MOVE, 300f, 510f, 0)
        val u2 = android.view.MotionEvent.obtain(0, 60, android.view.MotionEvent.ACTION_UP, 320f, 510f, 0)
        gv.dispatchTouchEvent(d2); gv.dispatchTouchEvent(m2); gv.dispatchTouchEvent(u2)
        frames(20)
        assertEquals(1, gv.world.lane.lane)
    }

    @Test
    fun crashGameOverAndContinueOncePerRun() {
        click(R.id.btnPlay)
        frames(30)
        crash()
        assertTrue(visible(R.id.panelGameOver))
        assertTrue("continue offered", visible(R.id.btnContinue))
        click(R.id.btnContinue)
        assertEquals(1, ads.rewardedShows)
        assertEquals(Screen.PLAYING, activity.screen)
        assertTrue(activity.gameView.world.invulnTimer > 0f)
        crash()
        assertFalse("continue only once per run", visible(R.id.btnContinue))
        click(R.id.btnRetry)
        assertEquals(Screen.PLAYING, activity.screen)
        crash()
        assertTrue("new run offers continue again", visible(R.id.btnContinue))
    }

    @Test
    fun continueHiddenWhenNoAdAvailable() {
        ads.rewardedReady = false
        click(R.id.btnPlay)
        crash()
        assertFalse(visible(R.id.btnContinue))
        click(R.id.btnRetry)
        assertEquals("game fully playable without ads", Screen.PLAYING, activity.screen)
    }

    @Test
    fun interstitialRespectsFrequencyCaps() {
        click(R.id.btnPlay)
        val showsAfterRun = IntArray(13)
        for (run in 1..12) {
            crash()
            click(R.id.btnRetry)
            idle()
            showsAfterRun[run] = ads.interstitialShows
            assertEquals(Screen.PLAYING, activity.screen)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40)) // ~40 s per run
        }
        // Runs 1-3: never. Run 4: first eligible. Then needs >=4 runs AND >=3 min since last.
        assertEquals(0, showsAfterRun[3])
        assertEquals(1, showsAfterRun[4])
        assertEquals(1, showsAfterRun[7])
        // run 8 is 4 runs later but only ~160 s later -> blocked by the 3 minute rule
        assertEquals(1, showsAfterRun[8])
        assertEquals(2, showsAfterRun[9])
        assertTrue(ads.interstitialShows <= 3)
    }

    @Test
    fun noInterstitialOffline() {
        ads.interstitialReady = false
        click(R.id.btnPlay)
        repeat(6) { crash(); click(R.id.btnRetry) }
        assertEquals(0, ads.interstitialShows)
        assertEquals(Screen.PLAYING, activity.screen)
    }

    @Test
    fun lifecyclePauseScreenOffAndRecreate() {
        click(R.id.btnPlay)
        frames(30)
        controller.pause()
        assertEquals("backgrounding pauses the run", Screen.PAUSED, activity.screen)
        controller.stop()
        controller.start().resume()
        idle()
        assertEquals("still paused after resume", Screen.PAUSED, activity.screen)
        // recreation (e.g. config change or "don't keep activities")
        controller.recreate()
        activity = controller.get()
        idle()
        assertEquals(Screen.MENU, activity.screen)
    }

    @Test
    fun processDeathRestoresToMenuWithPersistedBest() {
        click(R.id.btnPlay)
        frames(200)
        crash()
        val best = Prefs(app).best
        assertTrue(best > 0)
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val c2 = Robolectric.buildActivity(MainActivity::class.java).setup(state)
        val a2 = c2.get()
        assertEquals(Screen.MENU, a2.screen)
        val bestText = a2.findViewById<android.widget.TextView>(R.id.txtMenuBest).text.toString()
        assertTrue(bestText, bestText.replace(",", "").contains(best.toString()))
    }

    @Test
    fun settingsPersist() {
        click(R.id.btnSettings)
        assertEquals(Screen.SETTINGS, activity.screen)
        click(R.id.btnSound)
        click(R.id.btnVibration)
        assertFalse(Prefs(app).soundOn)
        assertFalse(Prefs(app).vibrationOn)
        click(R.id.btnSettingsBack)
        assertEquals(Screen.MENU, activity.screen)
    }

    @Test
    fun runCountPersists() {
        click(R.id.btnPlay)
        crash(); click(R.id.btnRetry)
        crash(); click(R.id.btnGameOverMenu)
        assertEquals(2, Prefs(app).totalRuns)
        assertEquals(Screen.MENU, activity.screen)
    }

    @Test
    fun backButtonPausesDuringPlay() {
        click(R.id.btnPlay)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(Screen.PAUSED, activity.screen)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(Screen.PLAYING, activity.screen)
    }
}
