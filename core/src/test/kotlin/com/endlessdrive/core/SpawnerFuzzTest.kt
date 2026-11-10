package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Fuzzes the spawner and checks every generated pattern with an INDEPENDENT validator:
 * a space-time reachability search over the exact simulated player trajectory, using the real
 * collision primitive, exact moving-obstacle positions and a full 120 ms lane-change animation
 * (no early chaining). Oil counts as blocking. If no collision-free path through the pattern
 * exists from some start lane, the pattern is impassable and the test fails.
 */
class SpawnerFuzzTest {

    private class Rec : SpawnSink {
        var n = 0
        var kind = IntArray(256); var lane = IntArray(256); var z = FloatArray(256)
        var spawnTick = IntArray(256); var spawnT = FloatArray(256)
        var curTick = 0; var curT = 0f
        var pickups = 0
        override fun spawnObstacle(kind: Int, lane: Int, z: Float) {
            if (n == this.kind.size) grow()
            this.kind[n] = kind; this.lane[n] = lane; this.z[n] = z
            spawnTick[n] = curTick; spawnT[n] = curT; n++
        }
        override fun spawnPickup(kind: Int, lane: Int, z: Float) { pickups++ }
        private fun grow() {
            val s = kind.size * 2
            kind = kind.copyOf(s); lane = lane.copyOf(s); z = z.copyOf(s)
            spawnTick = spawnTick.copyOf(s); spawnT = spawnT.copyOf(s)
        }
    }

    private val dt = 1f / 120f
    private val k = ceil(GameConfig.LANE_CHANGE_TIME / dt - 1e-4f).toInt()
    // state layout: 0..2 stationary lanes; then 4 transitions * k steps
    private val fromLane = intArrayOf(0, 1, 1, 2)
    private val toLane = intArrayOf(1, 0, 2, 1)
    private val nStates = 3 + 4 * k

    private fun stateX(s: Int): Float {
        if (s < 3) return GameConfig.laneX(s)
        val tr = (s - 3) / k
        val step = (s - 3) % k + 1
        val p = (step.toFloat() / k).coerceAtMost(1f)
        val a = GameConfig.laneX(fromLane[tr]); val b = GameConfig.laneX(toLane[tr])
        return a + (b - a) * LaneController.easeOutCubic(p)
    }

    private fun next(s: Int, out: IntArray): Int {
        var c = 0
        if (s < 3) {
            out[c++] = s
            for (tr in 0 until 4) if (fromLane[tr] == s) out[c++] = 3 + tr * k
        } else {
            val tr = (s - 3) / k
            val step = (s - 3) % k + 1
            out[c++] = if (step >= k) toLane[tr] else s + 1
        }
        return c
    }

    /** Returns null if passable, else a description of the failure. */
    private fun runPattern(seed: Long, t0: Float, rows: Int): String? {
        val sp = Spawner(Rng(seed))
        sp.reset(0f)
        val rec = Rec()
        var t = t0; var z = 0f; var tick = 0
        var zs = FloatArray(4096); var ts = FloatArray(4096)
        zs[0] = 0f; ts[0] = t
        var finishZ = Float.MAX_VALUE
        while (z < finishZ) {
            tick++
            t += dt
            val v = Difficulty.speed(t)
            z += v * dt
            rec.curTick = tick; rec.curT = t
            if (sp.rowsSpawned < rows) {
                sp.update(z, v, t, rec)
                if (sp.rowsSpawned >= rows) finishZ = sp.horizon + 3f
            }
            if (tick == zs.size) { zs = zs.copyOf(tick * 2); ts = ts.copyOf(tick * 2) }
            zs[tick] = z; ts[tick] = t
            if (tick > 2_000_000) return "runaway simulation"
        }
        return validate(rec, zs, ts, tick, "seed=$seed t0=$t0")
    }

    private fun validate(rec: Rec, zs: FloatArray, ts: FloatArray, lastTick: Int, tag: String): String? {
        // Structural checks: never 3 obstacles sharing a row.
        for (i in 0 until rec.n) {
            var same = 0
            for (j in 0 until rec.n) if (rec.z[j] == rec.z[i] && rec.spawnTick[j] == rec.spawnTick[i]) same++
            if (same >= 3) return "row with 3 blocked lanes at z=${rec.z[i]}"
        }

        // Forward reachability, run separately from each start lane.
        val buf = IntArray(4)
        val near = IntArray(rec.n.coerceAtLeast(1))
        for (startLane in 0 until 3) {
            var cur = BooleanArray(nStates); var nxt = BooleanArray(nStates)
            cur[startLane] = true
            for (i in 1..lastTick) {
                java.util.Arrays.fill(nxt, false)
                val pz = zs[i]; val ti = ts[i]
                var nn = 0
                for (o in 0 until rec.n) {
                    if (rec.spawnTick[o] > i) continue
                    val oz = rec.z[o] + ObstacleKind.SPEED[rec.kind[o]] * (ti - rec.spawnT[o] + dt)
                    if (abs(oz - pz) < 3f) near[nn++] = o
                }
                var any = false
                for (s in 0 until nStates) {
                    if (!cur[s]) continue
                    val c = next(s, buf)
                    for (q in 0 until c) {
                        val ns = buf[q]
                        if (nxt[ns]) continue
                        val x = stateX(ns)
                        var hit = false
                        for (m in 0 until nn) {
                            val o = near[m]
                            val oz = rec.z[o] + ObstacleKind.SPEED[rec.kind[o]] * (ti - rec.spawnT[o] + dt)
                            if (Collision.playerHits(x, pz, rec.kind[o], GameConfig.laneX(rec.lane[o]), oz)) { hit = true; break }
                        }
                        if (!hit) { nxt[ns] = true; any = true }
                    }
                }
                if (!any) return "no path from lane $startLane at t=${ts[i]} z=$pz ($tag)"
                val tmp = cur; cur = nxt; nxt = tmp
            }
        }
        if (rec.n == 0) return "pattern produced no obstacles"
        return null
    }

    @Test
    fun tenThousandPatternsAreAllPassable() {
        val meta = Rng(12345L)
        var failures = 0
        var firstFailure: String? = null
        val patterns = 10_000
        val start = System.nanoTime()
        for (p in 0 until patterns) {
            val seed = meta.nextLong()
            // Spread start times over grace, ramp-up and capped difficulty.
            val t0 = when (p % 4) {
                0 -> 0f
                1 -> meta.range(15f, 90f)
                2 -> meta.range(90f, 300f)
                else -> meta.range(300f, 1800f)
            }
            val err = runPattern(seed, t0, rows = 12)
            if (err != null) {
                failures++
                if (firstFailure == null) firstFailure = err
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        println("Spawner fuzz: $patterns patterns x 12 rows, failures=$failures, ${ms}ms")
        if (failures > 0) fail("$failures impassable patterns; first: $firstFailure")
    }

    /** Mutation check: the validator must reject genuinely impassable patterns. */
    @Test
    fun validatorRejectsImpassablePatterns() {
        fun straightRun(build: (Rec) -> Unit): String? {
            val rec = Rec(); build(rec)
            val n = 1200
            val zs = FloatArray(n + 1); val ts = FloatArray(n + 1)
            for (i in 0..n) { ts[i] = i * dt; zs[i] = i * dt * 8f }
            return validate(rec, zs, ts, n, "mutation")
        }
        // Full wall of barriers.
        assertTrue(straightRun { r -> for (l in 0..2) r.spawnObstacle(ObstacleKind.BARRIER, l, 20f) } != null)
        // Two staggered rows too close together to switch lanes (lane 0 blocked, then 1+2).
        assertTrue(straightRun { r ->
            r.spawnObstacle(ObstacleKind.BARRIER, 1, 20f); r.spawnObstacle(ObstacleKind.BARRIER, 2, 20f)
            r.spawnObstacle(ObstacleKind.BARRIER, 0, 21.2f); r.spawnObstacle(ObstacleKind.BARRIER, 1, 21.2f)
        } != null)
        // Same two rows with plenty of room: passable.
        assertTrue(straightRun { r ->
            r.spawnObstacle(ObstacleKind.BARRIER, 1, 20f); r.spawnObstacle(ObstacleKind.BARRIER, 2, 20f)
            r.spawnObstacle(ObstacleKind.BARRIER, 0, 30f); r.spawnObstacle(ObstacleKind.BARRIER, 1, 30f)
        } == null)
    }

    @Test
    fun gracePeriodOnlySpawnsSingleReadableObstacles() {
        for (seed in 1L..300L) {
            val sp = Spawner(Rng(seed)); sp.reset(0f)
            val rec = Rec()
            var t = 0f; var z = 0f
            while (t < GameConfig.GRACE_TIME - 1f) {
                t += dt; val v = Difficulty.speed(t); z += v * dt
                sp.update(z, v, t, rec)
            }
            for (i in 0 until rec.n) {
                assertTrue(rec.kind[i] == ObstacleKind.BARRIER || rec.kind[i] == ObstacleKind.SLOW_CAR)
                for (j in 0 until rec.n) if (j != i) assertTrue("double row in grace", rec.z[j] != rec.z[i])
            }
        }
    }

    @Test
    fun fairnessPredicate() {
        // From all lanes free, blocking two lanes needs 2 shifts to escape lane 0 -> lane 2.
        assertTrue(!Spawner.isFair(0b111, 0b011, 1))
        assertTrue(Spawner.isFair(0b111, 0b011, 2))
        assertTrue(Spawner.isFair(0b111, 0b101, 1)) // only middle free: 1 shift from edges
        assertTrue(!Spawner.isFair(0b111, 0b111, 2)) // never all three
        assertTrue(!Spawner.isFair(0b001, 0b001, 0)) // only free lane gets blocked, no time to move
        assertTrue(Spawner.isFair(0b001, 0b010, 0))
        assertTrue(Spawner.isFair(0b001, 0b110, 0)) // stay in lane 0
    }

    @Test
    fun shiftsScaleWithGapAndSpeed() {
        val sp = Spawner(Rng(1))
        assertEquals(0, sp.shiftsFor(0.5f, 12f))
        assertEquals(2, sp.shiftsFor(10f, 6f))
        assertTrue(sp.shiftsFor(3f, 12f) <= sp.shiftsFor(3f, 6f))
    }
}
