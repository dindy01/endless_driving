package com.endlessdrive.core

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fuzzes the traffic spawner + AI and proves, with an independent space-time reachability search,
 * that a path always exists. The validator knows nothing about how the spawner reasons: it steps
 * the real traffic simulation at 60 Hz and tracks the set of lateral positions a restricted player
 * could occupy: constant speed (no braking needed), staying fully on the paved road (no shoulder),
 * steering at only 80 % of the real lateral speed minus the worst-case curve drift.
 * If that set ever becomes empty the pattern is impassable.
 */
class SpawnerFuzzTest {
    private val lim = Cfg.ROAD_HALF - Cfg.PLAYER_HALF_W
    private val binW = 0.05f
    private val bins = (2 * lim / binW).toInt() + 1
    private val cur = BooleanArray(bins)
    private val nxt = BooleanArray(bins)
    private fun binX(i: Int) = -lim + i * binW

    private class Result(var clusters: Int = 0, var minSlack: Float = Float.MAX_VALUE, var forcedSwitches: Int = 0)

    /** Runs one pattern; returns null if passable, else a description. */
    private fun run(seed: Long, t0: Float, speedFactor: Float, seconds: Float, traffic: Traffic, res: Result): String? {
        val dt = 1f / 60f
        var time = t0
        var s = Cfg.START_S
        traffic.spawningEnabled = true
        traffic.reset(seed, s, time)
        java.util.Arrays.fill(cur, false)
        val start = ((Cfg.LANE_RIGHT_X + lim) / binW).toInt()
        cur[start] = true
        var lastLaneMask = 0
        val steps = (seconds / dt).toInt()
        for (step in 0 until steps) {
            time += dt
            val v = Difficulty.cruise(time) * speedFactor
            s += v * dt
            traffic.update(dt, s, time)
            // --- structural invariant: first other-lane obstacle ahead is at least `guard` away
            for (i in 0 until traffic.count) {
                val a = traffic.get(i)
                if (!a.alive || a.lane < 0) continue
                val lo = if (a.lane == 0) -Cfg.ROAD_HALF else 0f
                if (a.x - a.halfW < lo - 1e-3f || a.x + a.halfW > lo + Cfg.LANE_W + 1e-3f) return "obstacle leaves its lane (seed $seed)"
                for (j in i + 1 until traffic.count) {
                    val b = traffic.get(j)
                    if (!b.alive || b.lane < 0) continue
                    val gap = (b.s - b.halfL - (a.s + a.halfL)).toFloat()
                    if (b.lane == a.lane) {
                        if (gap < -1e-3f) return "same-lane overlap gap=$gap (seed $seed t=$time)"
                        continue
                    }
                    val slack = gap - b.guard
                    if (slack < res.minSlack) res.minSlack = slack
                    if (slack < -0.01f) return "guard violated: gap=$gap guard=${b.guard} (seed $seed t=$time)"
                    break
                }
            }
            // --- reachability
            val reach = 0.8f * (World.latSpeed(v) - Cfg.DRIFT_MAX) * dt
            val k = (reach / binW).toInt()
            assertTrue(k >= 1)
            for (i in 0 until bins) {
                var ok = false
                var j = maxOf(0, i - k)
                val hi = minOf(bins - 1, i + k)
                while (j <= hi) { if (cur[j]) { ok = true; break }; j++ }
                nxt[i] = ok
            }
            var laneMask = 0
            for (i in 0 until traffic.count) {
                val o = traffic.get(i)
                if (!o.alive) continue
                val ds = (o.s - s).toFloat()
                // inflate by one step of travel so nothing can tunnel between samples
                val hl = o.halfL + v * dt
                if (ds < -(hl + Cfg.PLAYER_HALF_L) || ds > hl + Cfg.PLAYER_HALF_L) continue
                if (o.lane >= 0) laneMask = laneMask or (1 shl o.lane)
                for (b in 0 until bins) {
                    if (nxt[b] && Collision.overlaps(0f, o.x - binX(b), 0f, o.halfW)) nxt[b] = false
                }
            }
            if (laneMask == 3) return "both lanes blocked side by side (seed $seed t=$time)"
            if (laneMask != 0 && lastLaneMask != 0 && laneMask != lastLaneMask) res.forcedSwitches++
            if (laneMask != 0) lastLaneMask = laneMask
            var any = false
            for (i in 0 until bins) { cur[i] = nxt[i]; if (nxt[i]) any = true }
            if (!any) return "no reachable position left (seed $seed t0=$t0 factor=$speedFactor t=$time s=$s)"
        }
        res.clusters += traffic.clustersSpawned
        return null
    }

    @Test
    fun tenThousandPatternsAreAllPassable() {
        val traffic = Traffic()
        val res = Result()
        val starts = floatArrayOf(0f, 0f, 20f, 60f, 150f, 400f, 1200f)
        val factors = floatArrayOf(1f, Cfg.NITRO_MULT, Cfg.SLOW_FACTOR, 1f, 1.15f)
        var patterns = 0
        for (seed in 1L..10_000L) {
            val t0 = starts[(seed % starts.size).toInt()]
            val f = factors[((seed / 7) % factors.size).toInt()]
            val err = run(seed * 2654435761L, t0, f, 16f, traffic, res)
            if (err != null) fail(err)
            patterns++
        }
        println("spawner fuzz: $patterns patterns, ${res.clusters} clusters, ${res.forcedSwitches} lane alternations, min guard slack ${res.minSlack} m")
        assertTrue(patterns == 10_000)
        assertTrue("fuzz should exercise many clusters", res.clusters > 100_000)
        assertTrue("fuzz should exercise forced lane changes", res.forcedSwitches > 20_000)
    }

    @Test
    fun longRunsStayPassable() {
        val traffic = Traffic()
        val res = Result()
        for (seed in 1L..12L) {
            val err = run(seed * 97L, 0f, if (seed % 2 == 0L) 1f else Cfg.NITRO_MULT, 600f, traffic, res)
            if (err != null) fail(err)
        }
    }

    /** The validator itself must be able to say "impassable", otherwise the fuzz proves nothing. */
    @Test
    fun validatorRejectsWallsAndTooTightAlternations() {
        // wall: two barriers side by side
        val t = Traffic()
        t.spawningEnabled = false
        t.reset(1, Cfg.START_S)
        t.placeForTest(Kind.BARRIER, 0, Cfg.START_S + 60.0)
        t.placeForTest(Kind.BARRIER, 1, Cfg.START_S + 60.0)
        assertTrue(simulateFixed(t) != null)
        // alternation with only 6 m between opposite-lane trucks
        val t2 = Traffic()
        t2.spawningEnabled = false
        t2.reset(1, Cfg.START_S)
        t2.placeForTest(Kind.TRUCK, 1, Cfg.START_S + 60.0)
        t2.placeForTest(Kind.TRUCK, 0, Cfg.START_S + 60.0 + 4.6 + 6.0 + 4.6)
        t2.placeForTest(Kind.TRUCK, 1, Cfg.START_S + 60.0 + 2 * (4.6 + 6.0 + 4.6))
        assertTrue(simulateFixed(t2) != null)
        // sanity: the same trucks 60 m apart are fine
        val t3 = Traffic()
        t3.spawningEnabled = false
        t3.reset(1, Cfg.START_S)
        t3.placeForTest(Kind.TRUCK, 1, Cfg.START_S + 60.0)
        t3.placeForTest(Kind.TRUCK, 0, Cfg.START_S + 130.0)
        assertTrue(simulateFixed(t3) == null)
    }

    private fun simulateFixed(traffic: Traffic): String? {
        val dt = 1f / 60f
        var s = Cfg.START_S
        java.util.Arrays.fill(cur, false)
        cur[((Cfg.LANE_RIGHT_X + lim) / binW).toInt()] = true
        val v = Cfg.VMAX
        for (step in 0 until 240) {
            s += v * dt
            val k = (0.8f * (World.latSpeed(v) - Cfg.DRIFT_MAX) * dt / binW).toInt()
            for (i in 0 until bins) {
                var ok = false
                for (j in maxOf(0, i - k)..minOf(bins - 1, i + k)) if (cur[j]) ok = true
                nxt[i] = ok
            }
            for (i in 0 until traffic.count) {
                val o = traffic.get(i)
                val ds = (o.s - s).toFloat()
                val hl = o.halfL + v * dt
                if (ds < -(hl + Cfg.PLAYER_HALF_L) || ds > hl + Cfg.PLAYER_HALF_L) continue
                for (b in 0 until bins) if (nxt[b] && Collision.overlaps(0f, o.x - binX(b), 0f, o.halfW)) nxt[b] = false
            }
            var any = false
            for (i in 0 until bins) { cur[i] = nxt[i]; if (nxt[i]) any = true }
            if (!any) return "blocked"
        }
        return null
    }
}
