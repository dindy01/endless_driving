package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.hypot

class MiscCoreTest {
    @Test
    fun roadIsContinuousGentleAndStreamsForever() {
        for (seed in 1L..20L) {
            val r = Road(seed)
            val f = RoadFrame(); val g = RoadFrame()
            var s = 0.0
            var maxCurv = 0f; var maxSlope = 0f
            while (s < 30_000.0) {
                r.frame(s, f); r.frame(s + 1.0, g)
                val d = hypot(g.x - f.x, g.z - f.z)
                assertEquals("unit speed parametrisation", 1.0, d, 0.02)
                maxCurv = maxOf(maxCurv, abs(r.curvature(s)))
                maxSlope = maxOf(maxSlope, abs(r.slope(s)))
                s += 1.0
            }
            assertTrue("gentle curves: radius >= 120 m ($maxCurv)", maxCurv < 1f / 120f)
            assertTrue("actually curves", maxCurv > 1f / 600f)
            assertTrue("small hills ($maxSlope)", maxSlope < 0.06f && maxSlope > 0.01f)
            assertEquals("starts straight", 0f, r.heading(40.0), 0f)
        }
    }

    @Test
    fun roadIsDeterministicPerSeed() {
        val a = Road(5); val b = Road(9); b.reset(5)
        val f = RoadFrame(); val g = RoadFrame()
        a.frame(5000.0, f); b.frame(5000.0, g)
        assertEquals(f.x, g.x, 0.0); assertEquals(f.z, g.z, 0.0); assertEquals(f.y, g.y, 0f)
    }

    @Test
    fun envCyclesThroughClearDuskNightRain() {
        val e = EnvCycle()
        e.update(10f); assertEquals(EnvCycle.CLEAR, e.phase); assertEquals(1f, e.w[EnvCycle.CLEAR], 0f)
        e.update(Cfg.ENV_PHASE_S + 30f); assertEquals(EnvCycle.DUSK, e.phase); assertEquals(1f, e.dusk, 0f)
        e.update(2 * Cfg.ENV_PHASE_S + 30f); assertEquals(1f, e.night, 0f)
        e.update(3 * Cfg.ENV_PHASE_S + 30f); assertEquals(1f, e.rain, 0f)
        e.update(4 * Cfg.ENV_PHASE_S + 30f); assertEquals(1f, e.w[EnvCycle.CLEAR], 0f)
        assertTrue(Cfg.ENV_PHASE_S in 60f..90f)
        // smooth: weights always sum to one and change slowly
        var t = 0f
        var prevNight = 0f
        while (t < 700f) {
            e.update(t)
            assertEquals(1f, e.w.sum(), 1e-5f)
            assertTrue(abs(e.night - prevNight) < 0.03f)
            prevNight = e.night
            t += 0.1f
        }
    }

    /** A simple look-ahead driver using the real controls. Used for the playability and allocation tests. */
    class Bot {
        fun steer(w: World): Float {
            val look = w.speed * 1.6f + 25f
            var near0 = Float.MAX_VALUE; var near1 = Float.MAX_VALUE
            for (i in 0 until w.traffic.count) {
                val o = w.traffic.get(i)
                if (!o.alive || o.lane < 0) continue
                val ds = (o.s - w.s).toFloat() + o.halfL + Cfg.PLAYER_HALF_L
                if (ds < 0f || ds > look) continue
                if (o.lane == 0) near0 = minOf(near0, ds) else near1 = minOf(near1, ds)
            }
            val cur = if (w.x < 0f) 0 else 1
            val lane = if (near0 == near1) cur else if (near0 > near1) 0 else 1
            val target = if (lane == 0) Cfg.LANE_LEFT_X else Cfg.LANE_RIGHT_X
            return ((target - w.x) * 1.5f).coerceIn(-1f, 1f)
        }
    }

    @Test
    fun aSimpleBotCanPlayForMinutes() {
        val dt = 1f / 60f
        val bot = Bot()
        var survived = 0
        var totalTime = 0f
        val runs = 60
        for (seed in 1L..runs) {
            val w = World()
            w.startRun(seed * 31L)
            var frames = 0
            while (w.state == World.RUNNING && frames < 60 * 180) {
                w.update(dt, bot.steer(w), false, false)
                frames++
            }
            totalTime += w.time
            if (w.state == World.RUNNING) survived++
        }
        println("bot: $survived/$runs runs survived 180 s, mean ${totalTime / runs} s")
        assertTrue("a naive look-ahead bot should usually survive 3 minutes ($survived/$runs)", survived >= runs * 8 / 10)
    }

    @Test
    fun updateDoesNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val w = World()
        val bot = Bot()
        w.startRun(11)
        val dt = 1f / 60f
        fun frames(n: Int) {
            var k = 0
            while (k < n) {
                w.update(dt, bot.steer(w), k % 500 < 20, k % 700 < 5)
                if (w.state == World.OVER) w.revive()
                k++
            }
        }
        frames(20_000) // warm up / JIT
        @Suppress("DEPRECATION") val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        frames(60_000)
        val after = bean.getThreadAllocatedBytes(id)
        println("allocated in 60,000 frames: ${after - before} bytes; t=${w.time}s score=${w.score}")
        assertTrue("hot loop allocated ${after - before} bytes", after - before < 4096)
    }
}
