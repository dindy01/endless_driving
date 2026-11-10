package com.endlessdrive.game

import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.endlessdrive.core.Kind
import com.endlessdrive.core.Mode
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
@Config(application = TestApp::class, qualifiers = "w891dp-h411dp-land-xxhdpi")
class MainActivityTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var a: MainActivity
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val ads get() = app.ads as FakeAds

    @Before
    fun setUp() {
        GameSurfaceView.glEnabled = false
        app.getSharedPreferences(Prefs.FILE, 0).edit().clear().commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        a = controller.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun click(id: Int) { a.findViewById<View>(id).performClick(); idle() }
    private fun visible(id: Int) = a.findViewById<View>(id).visibility == View.VISIBLE
    private fun frames(n: Int) { a.stepForTest(n); idle() }

    private fun crash() {
        frames(1)
        assertEquals(Mode.PLAYING, a.session.mode)
        val w = a.session.world
        w.suppressSpawningForTest()
        frames(170) // outlast any continue invulnerability
        w.placeForTest(Kind.TRUCK, if (w.player.x < 0f) 0 else 1, 25.0)
        frames(90)
        assertEquals(Screen.CRASHED, a.screen)
        frames(90)
        assertEquals(Screen.GAME_OVER, a.screen)
    }

    @Test
    fun menuThenPlayPauseResume() {
        assertEquals(Screen.MENU, a.screen)
        assertTrue(visible(R.id.panelMenu))
        assertTrue(ads.started)
        frames(30)
        assertEquals("attract demo runs behind the menu", Mode.ATTRACT, a.session.mode)
        click(R.id.btnPlay)
        frames(60)
        assertEquals(Screen.PLAYING, a.screen)
        assertEquals(Mode.PLAYING, a.session.mode)
        assertTrue(visible(R.id.btnPause))
        click(R.id.btnPause)
        frames(1)
        assertEquals(Mode.PAUSED, a.session.mode)
        val d = a.session.world.distance
        frames(60)
        assertEquals(d, a.session.world.distance, 0.0)
        click(R.id.btnResume)
        frames(10)
        assertTrue(a.session.world.distance > d)
    }

    private fun touch(action: Int, x: Float, y: Float, t: Long = 0) {
        val e = MotionEvent.obtain(0, t, action, x, y, 0)
        a.hud.dispatchTouchEvent(e)
        e.recycle()
    }

    @Test
    fun touchSteeringBrakeAndBoost() {
        click(R.id.btnPlay)
        frames(2)
        val h = a.hud
        val w = h.width.toFloat(); val ht = h.height.toFloat()
        assertTrue("hud laid out", w > 0 && ht > 0)
        // drag right in the steering zone -> analog steer > 0
        touch(MotionEvent.ACTION_DOWN, w * 0.2f, ht * 0.7f)
        touch(MotionEvent.ACTION_MOVE, w * 0.2f + 60f, ht * 0.7f, 10)
        val steer = a.session.input.steer
        assertTrue("analog partial steer $steer", steer > 0.1f && steer < 1f)
        val x0 = a.session.world.player.x
        frames(30)
        assertTrue("car moved right", a.session.world.player.x > x0)
        touch(MotionEvent.ACTION_UP, w * 0.2f + 60f, ht * 0.7f, 20)
        assertEquals(0f, a.session.input.steer, 0f)
        // brake button: bottom-right
        val d = h.resources.displayMetrics.density
        val bx = w - 28f * d - 50f * d; val by = ht - 26f * d - 50f * d
        touch(MotionEvent.ACTION_DOWN, bx, by)
        assertTrue(a.session.input.brake)
        touch(MotionEvent.ACTION_UP, bx, by, 10)
        assertFalse(a.session.input.brake)
        // boost button: left of brake
        val presses = a.session.input.boostPresses
        val gx = bx - 50f * d - 26f * d - 42f * d
        touch(MotionEvent.ACTION_DOWN, gx, by + 50f * d - 42f * d - 6f * d)
        touch(MotionEvent.ACTION_UP, gx, by, 10)
        assertEquals(presses + 1, a.session.input.boostPresses)
        frames(2)
        assertTrue(a.session.world.player.boosting)
    }

    @Test
    fun crashSlowMoGameOverAndContinueOncePerRun() {
        click(R.id.btnPlay)
        crash()
        assertTrue(visible(R.id.panelGameOver))
        assertTrue(visible(R.id.btnContinue))
        click(R.id.btnContinue)
        frames(1)
        assertEquals(1, ads.rewardedShows)
        assertEquals(Screen.PLAYING, a.screen)
        assertEquals(Mode.PLAYING, a.session.mode)
        crash()
        assertFalse("continue at most once per run", visible(R.id.btnContinue))
        click(R.id.btnRetry)
        assertEquals(Screen.PLAYING, a.screen)
        crash()
        assertTrue(visible(R.id.btnContinue))
    }

    @Test
    fun playableWithoutAds() {
        ads.rewardedReady = false
        ads.interstitialReady = false
        click(R.id.btnPlay)
        repeat(6) { crash(); assertFalse(visible(R.id.btnContinue)); click(R.id.btnRetry) }
        assertEquals(0, ads.interstitialShows)
        assertEquals(Screen.PLAYING, a.screen)
    }

    @Test
    fun interstitialFrequencyCaps() {
        click(R.id.btnPlay)
        val shows = IntArray(13)
        for (run in 1..12) {
            crash()
            click(R.id.btnRetry)
            idle()
            shows[run] = ads.interstitialShows
            assertEquals(Screen.PLAYING, a.screen)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        }
        assertEquals(0, shows[3])
        assertEquals(1, shows[4])
        assertEquals(1, shows[7])
        assertEquals("4 runs later but < 3 min", 1, shows[8])
        assertEquals(2, shows[9])
    }

    @Test
    fun lifecyclePauseScreenOffRecreate() {
        click(R.id.btnPlay)
        frames(30)
        controller.pause()
        frames(1)
        assertEquals(Screen.PAUSED, a.screen)
        assertEquals(Mode.PAUSED, a.session.mode)
        controller.stop(); controller.start().resume(); idle()
        assertEquals("stays paused after resume", Screen.PAUSED, a.screen)
        controller.recreate()
        a = controller.get()
        idle()
        assertEquals(Screen.MENU, a.screen)
    }

    @Test
    fun processDeathReturnsToMenuWithBestPersisted() {
        click(R.id.btnPlay)
        frames(240)
        crash()
        val best = Prefs(app).best
        assertTrue(best > 0)
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val c2 = Robolectric.buildActivity(MainActivity::class.java).setup(state)
        assertEquals(Screen.MENU, c2.get().screen)
        val txt = c2.get().findViewById<android.widget.TextView>(R.id.txtMenuBest).text.toString()
        assertTrue(txt, txt.replace(",", "").replace(".", "").contains(best.toString()))
    }

    @Test
    fun settingsPersist() {
        click(R.id.btnSettings)
        assertEquals(Screen.SETTINGS, a.screen)
        click(R.id.btnSound)
        click(R.id.btnVibration)
        click(R.id.btnQuality)
        click(R.id.btnControls)
        val p = Prefs(app)
        assertFalse(p.soundOn)
        assertFalse(p.vibrationOn)
        assertEquals(2, p.quality)
        assertEquals(2, a.gameView.qualityLevel)
        click(R.id.btnSettingsBack)
        assertEquals(Screen.MENU, a.screen)
    }

    @Test
    fun runCountPersistsAndBackButton() {
        click(R.id.btnPlay)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(Screen.PAUSED, a.screen)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(Screen.PLAYING, a.screen)
        crash(); click(R.id.btnRetry)
        crash(); click(R.id.btnGameOverMenu)
        assertEquals(2, Prefs(app).totalRuns)
        assertEquals(Screen.MENU, a.screen)
    }

    @Test
    fun retryIsFast() {
        click(R.id.btnPlay)
        crash()
        val t0 = System.nanoTime()
        click(R.id.btnRetry)
        frames(1)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(Mode.PLAYING, a.session.mode)
        assertTrue("retry took $ms ms", ms < 1000)
    }
}
