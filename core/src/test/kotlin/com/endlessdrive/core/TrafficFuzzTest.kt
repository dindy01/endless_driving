package com.endlessdrive.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Independent passability check. For each pattern the real Spawner + TrafficSystem run against a
 * scripted player speed profile (real PlayerCar longitudinal physics). In parallel, a reachability
 * search tracks every lateral position (0.2 m bins across the PAVED lanes only, shoulders excluded)
 * the player could occupy, moving at most LAT_GUARANTEE (4 m/s, below the car's real ability)
 * and colliding with the real hitboxes. If the reachable set ever becomes empty the pattern is
 * impassable. Profiles: nominal throttle, boost whenever ready, random brake/boost.
 */
class TrafficFuzzTest {
    private val dt = 1f / 60f
    private val dpEvery = 3 // DP step = 0.05 s -> 0.2 m lateral per step = 4 m/s
    private val nb = 27
    private fun binX(b: Int) = -2.6f + 0.2f * b

    class Validator(private val nb: Int, private val binX: (Int) -> Float) {
        var reach = BooleanArray(nb) { true }
        private var nxt = BooleanArray(nb)

        fun blocked(xb: Float, ps: Double, traffic: TrafficSystem): Boolean {
            for (c in traffic.cars) {
                if (c.active && Collision.hits(xb, ps, c.x, c.s, c.halfW, c.halfL)) return true
            }
            return false
        }

        fun init(ps: Double, traffic: TrafficSystem) {
            for (b in 0 until nb) reach[b] = !blocked(binX(b), ps, traffic)
        }

        /** Returns false if no lateral position is reachable any more. */
        fun step(ps: Double, traffic: TrafficSystem): Boolean {
            var any = false
            for (b in 0 until nb) {
                val from = reach[b] || (b > 0 && reach[b - 1]) || (b < nb - 1 && reach[b + 1])
                nxt[b] = from && !blocked(binX(b), ps, traffic)
                if (nxt[b]) any = true
            }
            val t = reach; reach = nxt; nxt = t
            return any
        }
    }

    private fun runPattern(seed: Long, t0: Float, profile: Int, seconds: Float): String? {
        val traffic = TrafficSystem()
        val pickups = Array(GameConfig.MAX_PICKUPS) { Pickup() }
        val spawner = Spawner(Rng(seed))
        val script = Rng(seed * 31 + 7)
        val car = PlayerCar()
        val input = Input()
        var t = t0
        car.reset(0.0, 1, Difficulty.baseSpeed(t), input)
        spawner.reset(car.s, t, traffic, pickups)
        val v = Validator(nb, ::binX)
        v.init(car.s, traffic)
        val ticks = (seconds / dt).toInt()
        for (i in 1..ticks) {
            t += dt
            if (profile == 1 && car.boostReady >= 1f) input.boostPresses++
            if (profile == 2 && i % 60 == 0) {
                val r = script.nextFloat()
                input.brake = r < 0.25f
                if (r > 0.8f) input.boostPresses++
            }
            car.update(dt, input, Difficulty.baseSpeed(t))
            spawner.update(car.s, t, traffic, pickups)
            traffic.step(dt, car.s, car.v)
            if (car.v > GameConfig.V_MAX_PLAYER + 1e-3f) return "player exceeded V_MAX_PLAYER"
            if (i % dpEvery == 0) {
                val m = traffic.invariantMargin()
                if (m < -1e-6) return "traffic invariant violated by $m m (seed=$seed t0=$t0 p=$profile)"
                if (!v.step(car.s, traffic)) return "no path at t=$t s=${car.s} (seed=$seed t0=$t0 profile=$profile)"
            }
        }
        return null
    }

    @Test
    fun tenThousandPatternsArePassable() {
        val meta = Rng(2024)
        val n = 10_000
        var failures = 0
        var first: String? = null
        val t0ms = System.nanoTime()
        var objects = 0L
        for (p in 0 until n) {
            val t0 = when (p % 4) {
                0 -> 0f
                1 -> meta.range(15f, 90f)
                2 -> meta.range(90f, 300f)
                else -> meta.range(300f, 1500f)
            }
            val profile = p % 3
            val err = runPattern(meta.nextLong(), t0, profile, 12f)
            if (err != null) {
                failures++
                if (first == null) first = err
            }
        }
        println("Traffic fuzz: $n patterns x 12 s, failures=$failures, ${(System.nanoTime() - t0ms) / 1_000_000} ms")
        if (failures > 0) fail("$failures impassable patterns; first: $first")
    }

    @Test
    fun validatorRejectsImpassableLayouts() {
        // Wall: trucks side by side (two cars side by side leave a lane-split gap) in both lanes, stationary, 100 m ahead.
        val traffic = TrafficSystem()
        fun put(lane: Int, s: Double, kind: Int) {
            val c = traffic.alloc()!!
            c.active = true; c.kind = kind; c.lane = lane; c.x = GameConfig.laneX(lane); c.s = s
            c.halfW = Kind.HALF_W[kind]; c.halfL = Kind.HALF_L[kind]; c.speed = 0f; c.desired = 0f
        }
        fun drive(): String? {
            val v = Validator(nb, ::binX)
            var s = 0.0
            v.init(s, traffic)
            for (i in 1..400) {
                s += 40.0 * dt * dpEvery
                if (!v.step(s, traffic)) return "blocked"
            }
            return null
        }
        put(0, 100.0, Kind.TRUCK); put(1, 100.0, Kind.TRUCK)
        assertNotNull(drive())
        // Staggered lane closures too close to switch lanes at 40 m/s (10 m apart).
        traffic.clear()
        put(0, 100.0, Kind.ROADWORKS); put(1, 100.0 + 25 + 25 + 10, Kind.ROADWORKS)
        assertNotNull(drive())
        // Same layout but with the invariant gap: passable.
        traffic.clear()
        put(0, 100.0, Kind.ROADWORKS); put(1, 100.0 + 25 + 25 + GameConfig.GAP_OTHER_LANE, Kind.ROADWORKS)
        assertNull(drive())
    }

    @Test
    fun guaranteeConstantsAreConsistent() {
        // G must cover a full lane change (3.5 m) at the guaranteed lateral speed at max player speed.
        val needed = GameConfig.V_MAX_PLAYER * (GameConfig.LANE_W / GameConfig.LAT_GUARANTEE) + 2 * GameConfig.CAR_HALF_L
        assertTrue("G=${GameConfig.GAP_OTHER_LANE} needed=$needed", GameConfig.GAP_OTHER_LANE >= needed)
        // The real car beats the guaranteed lateral speed even at top speed (slowest response).
        val car = PlayerCar()
        val input = Input()
        car.reset(0.0, 1, GameConfig.V_MAX_PLAYER, input)
        car.v = GameConfig.V_MAX_PLAYER
        input.steer = -1f
        var t = 0f
        val x0 = car.x
        while (x0 - car.x < GameConfig.LANE_W) { car.update(1f / 240f, input, GameConfig.V1); t += 1f / 240f }
        assertTrue("lane change took $t s", t <= GameConfig.LANE_W / GameConfig.LAT_GUARANTEE)
    }

    @Test
    fun graceOnlySpawnsMovingTraffic() {
        for (seed in 1L..200L) {
            val traffic = TrafficSystem()
            val pickups = Array(GameConfig.MAX_PICKUPS) { Pickup() }
            val sp = Spawner(Rng(seed))
            sp.reset(0.0, 0f, traffic, pickups)
            var s = 0.0
            var t = 0f
            while (t < GameConfig.GRACE_TIME - 1f) {
                t += 0.05f; s += Difficulty.baseSpeed(t) * 0.05
                sp.update(s, t, traffic, pickups)
                traffic.step(0.05f, s, Difficulty.baseSpeed(t))
            }
            for (c in traffic.cars) if (c.active) assertTrue("kind ${c.kind} in grace", c.kind == Kind.CAR || c.kind == Kind.TRUCK)
        }
    }
}
