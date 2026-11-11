package com.endlessdrive.desktop

import com.endlessdrive.core.Cfg
import com.endlessdrive.core.Kind
import com.endlessdrive.core.World
import com.endlessdrive.engine.Quality
import com.endlessdrive.engine.Renderer
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Headless preview: plays the real simulation with a simple look-ahead driver, renders it with the
 * real renderer and writes PNG frames. Also reports CPU frame cost and per-frame allocation.
 */
private const val W = 1280
private const val H = 592

private fun steer(w: World): Float {
    val look = w.speed * 1.6f + 25f
    var n0 = Float.MAX_VALUE; var n1 = Float.MAX_VALUE
    for (i in 0 until w.traffic.count) {
        val o = w.traffic.get(i)
        if (!o.alive || o.lane < 0) continue
        val ds = (o.s - w.s).toFloat() + o.halfL + Cfg.PLAYER_HALF_L
        if (ds < 0f || ds > look) continue
        if (o.lane == 0) n0 = minOf(n0, ds) else n1 = minOf(n1, ds)
    }
    val cur = if (w.x < 0f) 0 else 1
    val lane = if (n0 == n1) cur else if (n0 > n1) 0 else 1
    val target = if (lane == 0) Cfg.LANE_LEFT_X + 0.5f else Cfg.LANE_RIGHT_X - 0.5f
    return ((target - w.x) * 1.5f).coerceIn(-1f, 1f)
}

private fun save(dir: File, name: String) {
    val buf = BufferUtils.createByteBuffer(W * H * 4)
    GL11.glReadPixels(0, 0, W, H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf)
    val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until H) for (x in 0 until W) {
        val i = (y * W + x) * 4
        val r = buf.get(i).toInt() and 255; val g = buf.get(i + 1).toInt() and 255; val b = buf.get(i + 2).toInt() and 255
        img.setRGB(x, H - 1 - y, (r shl 16) or (g shl 8) or b)
    }
    ImageIO.write(img, "png", File(dir, "$name.png"))
    println("wrote ${File(dir, "$name.png")}")
}

fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "shots" }).apply { mkdirs() }
    val quality = Quality.of(args.getOrElse(1) { "2" }.toInt())
    check(GLFW.glfwInit()) { "GLFW init failed (no display? run under xvfb-run)" }
    GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 2)
    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1)
    val win = GLFW.glfwCreateWindow(W, H, "Endless Drive preview", 0, 0)
    check(win != 0L) { "window creation failed" }
    GLFW.glfwMakeContextCurrent(win)
    GL.createCapabilities()
    println("GL: " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION))

    val renderer = Renderer(quality)
    val es = args.getOrElse(2) { "es" } == "es"
    println("shader dialect: " + if (es) "GLSL ES 1.00 (same source as Android)" else "GLSL 1.20")
    renderer.init(LwjglGl(es))
    renderer.resize(W, H)
    val world = World()
    val dt = 1f / 60f

    fun frames(n: Int, nitro: Boolean = false, play: Boolean = true) {
        repeat(n) {
            world.update(dt, if (play) steer(world) else 0f, false, nitro)
            if (world.state == World.OVER && play) world.revive()
            renderer.draw(world, dt)
        }
    }

    // menu backdrop
    frames(240, play = false); save(dir, "01_menu_attract")
    // day
    world.startRun(21)
    frames(60 * 9); save(dir, "02_day_start")
    frames(60 * 22); save(dir, "03_day_traffic")
    frames(30, nitro = true); frames(50); save(dir, "04_nitro_speedlines")
    // dusk / night / rain at higher difficulty
    world.forceEnvTime(Cfg.ENV_PHASE_S + 30f); frames(60 * 12); save(dir, "05_dusk")
    world.forceEnvTime(2 * Cfg.ENV_PHASE_S + 30f); frames(60 * 14); save(dir, "06_night")
    frames(60 * 7); save(dir, "07_night_b")
    world.forceEnvTime(3 * Cfg.ENV_PHASE_S + 30f); frames(60 * 10); save(dir, "08_rain")
    // shoulder dust
    world.forceEnvTime(20f)
    world.traffic.clearAll(); world.traffic.spawningEnabled = false
    world.x = Cfg.ROAD_HALF + 0.7f
    repeat(40) { world.update(dt, 0f, false, false); world.x = Cfg.ROAD_HALF + 0.7f; renderer.draw(world, dt) }
    save(dir, "09_shoulder")
    // roadworks close-up and crash
    world.x = Cfg.LANE_RIGHT_X
    world.traffic.placeForTest(Kind.SIGN, 0, world.s + 30.0)
    world.traffic.placeForTest(Kind.CONE, 0, world.s + 36.0)
    world.traffic.placeForTest(Kind.BARRIER, 0, world.s + 44.0)
    world.traffic.placeForTest(Kind.TRUCK, 1, world.s + 75.0, 12f)
    world.traffic.placeForTest(Kind.VAN, 0, world.s + 62.0, 0f).hazard = true
    repeat(20) { world.update(dt, 0f, false, false); renderer.draw(world, dt) }
    save(dir, "10_roadworks")
    world.traffic.placeForTest(Kind.CAR, 1, world.s + 16.0, 6f)
    var n = 0
    while (world.state == World.RUNNING && n < 300) { world.update(dt, 0f, false, false); renderer.draw(world, dt); n++ }
    repeat(14) { world.update(dt, 0f, false, false); renderer.draw(world, dt) }
    save(dir, "11_crash")

    // ---- performance and allocation of sim + render (CPU side; software GL, so GPU time is not representative)
    world.startRun(5)
    frames(600)
    val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    @Suppress("DEPRECATION") val id = Thread.currentThread().id
    val a0 = bean.getThreadAllocatedBytes(id)
    val t0 = System.nanoTime()
    frames(1200)
    GL11.glFinish()
    val ms = (System.nanoTime() - t0) / 1e6 / 1200
    val alloc = bean.getThreadAllocatedBytes(id) - a0
    println("perf[${quality.name}]: %.3f ms/frame (sim + render submit, software GL), allocated %d bytes over 1200 frames (%.1f B/frame)".format(ms, alloc, alloc / 1200.0))

    // retry cost: fresh world + rebuild every chunk
    val r0 = System.nanoTime()
    world.startRun(77)
    world.update(dt, 0f, false, false)
    renderer.draw(world, dt)
    GL11.glFinish()
    println("retry (reset world + rebuild all chunks + first frame): %.1f ms".format((System.nanoTime() - r0) / 1e6))
    GLFW.glfwDestroyWindow(win)
    GLFW.glfwTerminate()
}
