package com.endlessdrive.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.endlessdrive.core.ObstacleKind
import com.endlessdrive.core.PickupKind
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Renders real frames with Robolectric's native graphics and saves PNGs as visual evidence. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w393dp-h852dp-port-xxhdpi")
class ScreenshotTest {
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun snap(root: View, name: String): Bitmap {
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun distinctColors(b: Bitmap): Int {
        val set = HashSet<Int>()
        var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { set.add(b.getPixel(x, y)); x += 7 }; y += 7 }
        return set.size
    }

    @Test
    fun renderScreens() {
        GameView.loopEnabled = false
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        val root = a.findViewById<View>(R.id.root)
        val idle = { shadowOf(Looper.getMainLooper()).idle() }
        val frames = { n: Int -> repeat(n) { a.gameView.advance(1f / 60f) } }
        frames(60); idle()
        // Let the panel fade-in animators finish.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        val menu = snap(root, "01_menu")
        assertTrue("menu should not be blank", distinctColors(menu) > 50)

        a.findViewById<View>(R.id.btnPlay).performClick(); idle()
        val w = a.gameView.world
        frames(60 * 22) // past the grace period; spawner active (may crash -> continue)
        if (a.screen != MainActivity.Screen.PLAYING) {
            a.findViewById<View>(R.id.btnContinue).performClick(); idle()
        }
        w.disableSpawningForTest()
        for (o in w.obstacles) o.active = false
        w.placeObstacleForTest(ObstacleKind.SLOW_CAR, 0, w.distance + 4f)
        w.placeObstacleForTest(ObstacleKind.TRUCK, 2, w.distance + 6.5f)
        w.placeObstacleForTest(ObstacleKind.BARRIER, 1, w.distance + 8.5f)
        w.placeObstacleForTest(ObstacleKind.OIL, 0, w.distance + 8.5f)
        for (i in 0 until 4) w.placePickupForTest(PickupKind.COIN, 1, w.distance + 3f + i * 0.65f)
        w.placePickupForTest(PickupKind.SHIELD, 2, w.distance + 2f)
        frames(1)
        val day = snap(root, "02_gameplay_day")
        assertTrue(distinctColors(day) > 50)

        // Night: advance the clock past the 60 s mark with an empty road.
        for (o in w.obstacles) o.active = false
        for (p in w.pickups) p.active = false
        w.disableSpawningForTest()
        while (w.time < 68f) frames(60)
        assertTrue("still driving", a.screen == MainActivity.Screen.PLAYING)
        w.placeObstacleForTest(ObstacleKind.SLOW_CAR, 2, w.distance + 4f)
        w.placeObstacleForTest(ObstacleKind.BARRIER, 0, w.distance + 7f)
        w.placeObstacleForTest(ObstacleKind.TRUCK, 1, w.distance + 9f)
        frames(1)
        snap(root, "03_gameplay_night")

        // Crash + game over
        for (o in w.obstacles) o.active = false
        w.placeObstacleForTest(ObstacleKind.BARRIER, w.lane.lane, w.distance + 1.2f)
        frames(12)
        snap(root, "04_crash")
        frames(80); idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        snap(root, "05_game_over")

        a.findViewById<View>(R.id.btnGameOverMenu).performClick(); idle()
        a.findViewById<View>(R.id.btnSettings).performClick(); idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        snap(root, "06_settings")
        println("Screenshots written to ${outDir.absolutePath}")
    }
}
