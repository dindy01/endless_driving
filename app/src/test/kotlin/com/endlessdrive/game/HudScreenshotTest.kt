package com.endlessdrive.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.endlessdrive.core.Kind
import com.endlessdrive.core.PickupKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the Android UI layer (HUD, controls, panels) with real pixels. The GL scene is not
 * available under Robolectric, so the UI is drawn over transparency; tools/composite.py merges
 * these with the headless GL frames from :render for full-screen previews.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w800dp-h360dp-land-xhdpi")
class HudScreenshotTest {
    private val out = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun snap(root: View, name: String) {
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.findViewById<View>(R.id.gameView).visibility = View.INVISIBLE
        root.setBackgroundColor(0)
        root.draw(Canvas(bmp))
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun uiLayers() {
        GameSurfaceView.glEnabled = false
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        val root = a.findViewById<View>(R.id.root)
        val idle = { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400)) }
        idle()
        snap(root, "ui_01_menu")
        a.findViewById<View>(R.id.btnPlay).performClick(); idle()
        a.stepForTest(1)
        val w = a.session.world
        w.suppressSpawningForTest()
        a.stepForTest(60 * 25)
        w.placePickupForTest(PickupKind.SHIELD, w.player.x, 8.0)
        w.player.x = 0.9f
        w.placeForTest(Kind.CAR, 0, 10.0, 5f)
        w.placeForTest(Kind.CAR, 0, 50.0, 5f)
        a.stepForTest(150)
        a.session.input.boostPresses++
        a.stepForTest(10)
        idle()
        snap(root, "ui_02_hud")
        a.findViewById<View>(R.id.btnPause).performClick(); a.stepForTest(1); idle()
        println("after pause click: screen=${a.screen} pausePanel=${a.findViewById<View>(R.id.panelPause).visibility} alpha=${a.findViewById<View>(R.id.panelPause).alpha}")
        snap(root, "ui_03_pause")
        a.findViewById<View>(R.id.btnResume).performClick(); a.stepForTest(1); idle()
        a.stepForTest(400) // let the shield run out
        w.placeForTest(Kind.TRUCK, if (w.player.x < 0) 0 else 1, 30.0)
        a.stepForTest(200); idle()
        println("game over: screen=${a.screen}")
        snap(root, "ui_04_game_over")
        a.findViewById<View>(R.id.btnGameOverMenu).performClick(); idle()
        a.findViewById<View>(R.id.btnSettings).performClick(); idle()
        snap(root, "ui_05_settings")
    }
}
