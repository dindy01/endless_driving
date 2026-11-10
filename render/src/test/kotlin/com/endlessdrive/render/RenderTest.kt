package com.endlessdrive.render

import com.endlessdrive.core.Kind
import com.endlessdrive.core.GameSession
import com.endlessdrive.core.Mode
import com.endlessdrive.core.PickupKind
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Renders real frames through the production SceneRenderer + shaders on a headless GLES 3 context
 * (Mesa llvmpipe) and saves them as PNGs. Skipped (not failed) if no EGL/GLES is installed.
 */
class RenderTest {
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")

    private fun distinctColors(px: IntArray): Int {
        val set = HashSet<Int>()
        var i = 0
        while (i < px.size) { set.add(px[i] and 0xF8F8F8); i += 13 }
        return set.size
    }

    @Test
    fun rendersScenesHeadlessly() {
        val ctx = HeadlessContext.tryCreate(1280, 720)
        assumeTrue("no headless GLES available", ctx != null)
        ctx!!
        println("GL: ${ctx.renderer}")
        val gl = LwjglGL()
        val r = SceneRenderer()
        r.onSurfaceCreated(gl)
        r.onSurfaceChanged(gl, ctx.width, ctx.height)
        val s = GameSession()
        fun frames(n: Int, render: Boolean = false) = repeat(n) {
            s.frame(1f / 60f)
            if (render) r.render(gl, s, 1f / 60f)
        }

        frames(120, render = true)
        snap(ctx, gl, r, s, "01_attract")

        s.requestStart(77)
        frames(60 * 20, render = false) // drive 20 s (spawner populates)
        if (s.mode != Mode.PLAYING) { s.requestRevive(); frames(1) }
        // stage a readable scene ahead
        val w = s.world
        w.suppressSpawningForTest()
        w.placeForTest(Kind.CAR, 0, 25.0, 15f)
        w.placeForTest(Kind.TRUCK, 1, 70.0, 12f)
        w.placeForTest(Kind.ROADWORKS, 0, 160.0)
        w.placeForTest(Kind.BARRIER, 1, 110.0)
        for (i in 0 until 5) w.placePickupForTest(PickupKind.COIN, 1.75f, 20.0 + i * 7)
        w.placePickupForTest(PickupKind.SHIELD, -1.75f, 50.0)
        s.input.steer = -0.3f
        frames(10, render = true)
        s.input.steer = 0f
        val day = snap(ctx, gl, r, s, "02_day_clear")
        assertTrue("day frame looks blank", distinctColors(day) > 200)

        // advance through the weather/day cycle with an empty road (player keeps driving)
        fun advanceEnv(toTime: Float, name: String) {
            w.clearTrafficForTest()
            while (w.time < toTime && s.mode == Mode.PLAYING) frames(30)
            w.placeForTest(Kind.CAR, 1, 30.0, 14f)
            w.placeForTest(Kind.STOPPED_CAR, 0, 90.0)
            w.placeForTest(Kind.TRUCK, 1, 140.0, 10f)
            frames(20, render = true)
            snap(ctx, gl, r, s, name)
        }
        advanceEnv(Environment.PHASE + 20f, "03_dusk")
        advanceEnv(Environment.PHASE * 2 + 20f, "04_night")
        advanceEnv(Environment.PHASE * 3 + 25f, "05_rain")

        // crash
        w.clearTrafficForTest()
        w.placeForTest(Kind.TRUCK, if (w.player.x < 0) 0 else 1, 12.0)
        var guard = 0
        while (s.mode == Mode.PLAYING && guard++ < 300) frames(1, render = true)
        frames(12, render = true)
        snap(ctx, gl, r, s, "06_crash")

        // performance smoke (software renderer, NOT representative of a phone GPU)
        s.requestStart(5); frames(1)
        val t0 = System.nanoTime()
        repeat(120) { frames(1, render = true) }
        val ms = (System.nanoTime() - t0) / 1e6 / 120
        println("avg frame (sim+render, llvmpipe CPU raster) = ${"%.1f".format(ms)} ms; chunk rebuilds=${r.chunks.rebuilds}")
        for (lvl in 0..2) {
            r.quality = Quality.of(lvl)
            frames(30, render = true)
            snap(ctx, gl, r, s, "07_quality_$lvl")
        }
    }

    private fun snap(ctx: HeadlessContext, gl: GL, r: SceneRenderer, s: GameSession, name: String): IntArray {
        val px = ctx.readPixels()
        ctx.save(px, File(outDir, "$name.png"))
        return px
    }
}
