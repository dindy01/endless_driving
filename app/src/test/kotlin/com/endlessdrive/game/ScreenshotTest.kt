package com.endlessdrive.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.endlessdrive.core.Kind
import com.endlessdrive.core.Pickup
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Android UI layer (HUD, controls, menus) with Robolectric's native graphics.
 * There is no GPU here, so the 3D scene behind it is absent (transparent background -> grey);
 * the 3D frames come from the desktop harness (:desktop:shots).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w800dp-h360dp-land-xhdpi")
class ScreenshotTest {
    private val dir = File(System.getProperty("shotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun snap(root: View, name: String): Int {
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF5A6470.toInt())
        root.draw(c)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val colors = HashSet<Int>()
        var y = 0
        while (y < bmp.height) { var x = 0; while (x < bmp.width) { colors.add(bmp.getPixel(x, y)); x += 5 }; y += 5 }
        return colors.size
    }

    @Test
    fun uiLayerRenders() {
        GameSurface.glEnabled = false
        Audio.threadEnabled = false
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.findViewById<View>(R.id.root)
        root.setBackgroundColor(0)
        val idle = { shadowOf(Looper.getMainLooper()).idle() }
        assertTrue(snap(root, "ui_1_menu") > 20)
        a.findViewById<View>(R.id.btnPlay).performClick(); idle()
        val w = a.controller.world
        a.stepFrames(240)
        w.traffic.spawningEnabled = false; w.traffic.clearAll()
        // near miss + shield + nitro so every HUD element is visible
        w.x = 0.3f
        w.traffic.placeForTest(Kind.CAR, 0, w.s + 12.0, 4f)
        w.traffic.placeForTest(Kind.CAR, 0, w.s + 26.0, 4f)
        w.pickups[0].apply { kind = Pickup.SHIELD; s = w.s + 6.0; x = 0.3f; active = true }
        a.controller.nitro = true
        a.stepFrames(75)
        assertTrue(w.combo.count >= 2 && w.shieldTime > 0f && w.boosting)
        assertTrue(snap(root, "ui_2_hud") > 40)
        a.controller.nitro = false
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue(snap(root, "ui_3_pause") > 20)
        a.findViewById<View>(R.id.btnResume).performClick(); idle()
        a.stepFrames(400)
        w.traffic.placeForTest(Kind.BARRIER, if (w.x < 0f) 0 else 1, w.s + 25.0)
        a.stepFrames(300); idle()
        assertTrue(snap(root, "ui_4_game_over") > 20)
        a.findViewById<View>(R.id.btnOverMenu).performClick(); idle()
        a.findViewById<View>(R.id.btnSettings).performClick(); idle()
        assertTrue(snap(root, "ui_5_settings") > 20)
        println("UI screenshots in $dir")
    }
}
