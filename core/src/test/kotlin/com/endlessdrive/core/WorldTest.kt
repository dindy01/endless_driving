package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WorldTest {
    private val dt = 1f / 60f

    /** A running world with an empty road so tests can place exactly what they need. */
    private fun emptyWorld(): World {
        val w = World()
        w.startRun(3)
        w.traffic.spawningEnabled = false
        w.traffic.clearAll()
        for (p in w.pickups) p.active = false
        return w
    }

    private fun World.step(n: Int, steer: Float = 0f, brake: Boolean = false, nitro: Boolean = false): Int {
        var ev = 0
        repeat(n) { update(dt, steer, brake, nitro); ev = ev or events }
        return ev
    }

    /** Like step() but holds the right lane against the curve drift. */
    private fun World.hold(n: Int, brake: Boolean = false, nitro: Boolean = false): Int {
        var ev = 0
        repeat(n) { update(dt, ((Cfg.LANE_RIGHT_X - x) * 2f).coerceIn(-1f, 1f), brake, nitro); ev = ev or events }
        return ev
    }

    // ---------------------------------------------------------------- collision rules

    @Test
    fun overlapRule() {
        assertTrue(Collision.overlaps(0f, 0f, 2f, 1f))
        assertTrue(Collision.overlaps(3.5f, 1.5f, 2.1f, 0.95f))
        assertFalse("clear ahead", Collision.overlaps(4.3f, 0f, 2.1f, 0.95f))
        assertFalse("clear to the side", Collision.overlaps(0f, 1.8f, 2.1f, 0.95f))
        assertFalse("adjacent lane centres never touch", Collision.overlaps(0f, Cfg.LANE_W, 4.6f, 1.25f))
    }

    @Test
    fun railAndShoulderRules() {
        assertFalse(Collision.onShoulder(Cfg.LANE_RIGHT_X))
        assertTrue(Collision.onShoulder(Cfg.ROAD_HALF))
        assertFalse(Collision.hitsRail(Cfg.ROAD_HALF + 1f))
        assertTrue(Collision.hitsRail(Cfg.RAIL_X - 0.5f))
        assertTrue(Collision.hitsRail(-(Cfg.RAIL_X - 0.5f)))
    }

    @Test
    fun hittingTrafficCrashesThenSlowMoThenGameOver() {
        val w = emptyWorld()
        w.traffic.placeForTest(Kind.CAR, 1, w.s + 30.0)
        var ev = 0
        var frames = 0
        while (w.state == World.RUNNING && frames < 300) { ev = ev or w.step(1); frames++ }
        assertEquals(World.CRASHING, w.state)
        assertTrue(ev and Ev.CRASH != 0)
        assertTrue("debris particles", w.particles.activeCount() > 20)
        // slow motion: the crash sequence lasts CRASH_DURATION of real time
        val before = w.time
        ev = w.step((Cfg.CRASH_DURATION / dt).toInt() + 2)
        assertEquals(World.OVER, w.state)
        assertTrue(ev and Ev.GAME_OVER != 0)
        assertEquals("run clock stops at the crash", before, w.time, 0f)
    }

    @Test
    fun conesAndBarriersCrashToo() {
        for (k in intArrayOf(Kind.CONE, Kind.BARRIER, Kind.SIGN, Kind.TRUCK, Kind.VAN)) {
            val w = emptyWorld()
            w.traffic.placeForTest(k, 1, w.s + 25.0)
            w.step(200)
            assertTrue("kind $k", w.state != World.RUNNING)
        }
    }

    @Test
    fun otherLaneIsSafe() {
        val w = emptyWorld()
        w.traffic.placeForTest(Kind.TRUCK, 0, w.s + 30.0)
        w.step(240)
        assertEquals(World.RUNNING, w.state)
    }

    @Test
    fun shoulderSlowsTheCarAndGuardRailCrashes() {
        val w = emptyWorld()
        w.step(120)
        val cruising = w.speed
        w.x = Cfg.ROAD_HALF + 0.6f
        w.step(90)
        assertTrue(w.onShoulder)
        assertEquals(World.RUNNING, w.state)
        assertTrue("slowed: ${w.speed} vs $cruising", w.speed < cruising * 0.75f)
        assertEquals(w.cruise * Cfg.SLOW_FACTOR, w.speed, 0.2f)
        // keep steering outwards: hits the rail
        w.step(120, steer = 1f)
        assertTrue(w.state != World.RUNNING)
    }

    @Test
    fun parkedShoulderCarCrashesOnlyIfYouLeaveTheRoad() {
        val w = emptyWorld()
        w.x = Cfg.ROAD_HALF - Cfg.PLAYER_HALF_W // hugging the edge line, still on the road
        w.traffic.placeForTest(Kind.CAR, -1, w.s + 30.0)
        w.step(200)
        assertEquals(World.RUNNING, w.state)
        val w2 = emptyWorld()
        w2.x = Cfg.ROAD_HALF + 1.0f
        w2.traffic.placeForTest(Kind.CAR, -1, w2.s + 30.0)
        w2.step(200)
        assertTrue(w2.state != World.RUNNING)
    }

    // ---------------------------------------------------------------- scoring

    @Test
    fun scoreIsDistancePlusBonuses() {
        val w = emptyWorld()
        w.step(300)
        assertEquals(w.distance.toInt(), w.score)
        assertTrue(w.distance > 80f)
        w.pickups[0].apply { kind = Pickup.COIN; s = w.s + 10.0; x = w.x; active = true }
        val ev = w.step(60)
        assertTrue(ev and Ev.COIN != 0)
        assertEquals(1, w.coins)
        assertEquals(w.distance.toInt() + Cfg.COIN_POINTS, w.score)
    }

    @Test
    fun nearMissRequiresClosePassAtSpeed() {
        // close pass in the adjacent lane: player at the lane's inner edge
        val w = emptyWorld()
        w.x = 0.3f
        w.traffic.placeForTest(Kind.CAR, 0, w.s + 25.0, speed = 5f)
        val ev = w.step(150)
        assertEquals(World.RUNNING, w.state)
        assertTrue(ev and Ev.NEAR_MISS != 0)
        assertEquals(1, w.nearMisses)
        assertEquals(Cfg.NEAR_MISS_POINTS, w.lastNearMissPoints)
        assertEquals(w.distance.toInt() + Cfg.NEAR_MISS_POINTS, w.score)

        // a wide pass does not count
        val far = emptyWorld()
        far.x = Cfg.ROAD_HALF - Cfg.PLAYER_HALF_W
        far.traffic.placeForTest(Kind.CAR, 0, far.s + 25.0, speed = 5f)
        far.step(150)
        assertEquals(0, far.nearMisses)

        // a slow pass does not count (relative speed below the threshold)
        val slow = emptyWorld()
        slow.x = 0.3f
        slow.step(60)
        slow.traffic.placeForTest(Kind.CAR, 0, slow.s + 8.0, speed = slow.speed - 4f)
        slow.step(400)
        assertEquals(0, slow.nearMisses)

        // cones are not traffic
        val cone = emptyWorld()
        cone.x = 0.3f
        cone.traffic.placeForTest(Kind.CONE, 0, cone.s + 25.0)
        cone.traffic.get(cone.traffic.count - 1).x = -0.95f
        cone.step(150)
        assertEquals(0, cone.nearMisses)
    }

    @Test
    fun comboMultiplierBuildsAndExpires() {
        val w = emptyWorld()
        w.x = 0.3f
        for (k in 0 until 3) w.traffic.placeForTest(Kind.CAR, 0, w.s + 25.0 + k * 20.0, speed = 2f)
        w.step(200)
        assertEquals(3, w.nearMisses)
        assertEquals(3, w.combo.multiplier)
        assertEquals(Cfg.NEAR_MISS_POINTS * (1 + 2 + 3), w.bonus)
        w.step((Cfg.COMBO_WINDOW / dt).toInt() + 5)
        assertEquals("combo expired", 1, w.combo.multiplier)
        assertEquals(0, w.combo.count)
    }

    @Test
    fun comboIsCapped() {
        val c = Combo()
        var last = 0
        repeat(20) { last = c.onNearMiss() }
        assertEquals(Cfg.COMBO_MAX, c.multiplier)
        assertEquals(Cfg.NEAR_MISS_POINTS * Cfg.COMBO_MAX, last)
        assertEquals(20, c.best)
    }

    // ---------------------------------------------------------------- shield / nitro / steering

    @Test
    fun shieldAbsorbsExactlyOneHitAndLastsFiveSeconds() {
        val w = emptyWorld()
        w.pickups[0].apply { kind = Pickup.SHIELD; s = w.s + 5.0; x = w.x; active = true }
        var ev = w.step(30)
        assertTrue(ev and Ev.SHIELD != 0)
        assertEquals(Cfg.SHIELD_DURATION, w.shieldTime, 0.5f)
        w.traffic.placeForTest(Kind.BARRIER, 1, w.s + 20.0)
        ev = w.step(60)
        assertTrue(ev and Ev.SHIELD_BREAK != 0)
        assertEquals(World.RUNNING, w.state)
        assertEquals(0f, w.shieldTime, 0f)
        // second hit after the brief invulnerability is fatal
        w.step(((Cfg.INVULN_AFTER_SHIELD) / dt).toInt() + 2)
        w.traffic.placeForTest(Kind.BARRIER, 1, w.s + 20.0)
        w.step(90)
        assertTrue(w.state != World.RUNNING)

        val t = emptyWorld()
        t.pickups[0].apply { kind = Pickup.SHIELD; s = t.s + 5.0; x = t.x; active = true }
        t.step(30)
        t.step((Cfg.SHIELD_DURATION / dt).toInt())
        assertEquals("expires", 0f, t.shieldTime, 0f)
    }

    @Test
    fun nitroBoostsThenCoolsDown() {
        val w = emptyWorld()
        w.hold(240)
        val cruise = w.speed
        assertEquals(1f, w.boostCharge, 0f)
        var ev = w.hold(1, nitro = true)
        assertTrue(ev and Ev.NITRO != 0)
        w.hold(100, nitro = true)
        assertTrue("faster: ${w.speed} vs $cruise", w.speed > cruise * 1.2f)
        assertTrue(w.speed <= w.cruise * Cfg.NITRO_MULT + 1e-3f)
        w.hold(((Cfg.NITRO_DURATION) / dt).toInt(), nitro = true)
        assertFalse("holding the button does not retrigger during cooldown", w.boosting)
        assertTrue(w.boostCharge < 1f)
        ev = w.hold((Cfg.NITRO_COOLDOWN / dt).toInt() + 2)
        assertTrue(ev and Ev.NITRO_READY != 0)
        assertEquals(1f, w.boostCharge, 0f)
        assertEquals(w.cruise, w.speed, 0.5f)
    }

    @Test
    fun brakeSlowsToFloorAboveTraffic() {
        val w = emptyWorld()
        w.hold(200)
        w.hold(120, brake = true)
        assertEquals(w.cruise * Cfg.SLOW_FACTOR, w.speed, 0.05f)
        assertTrue(w.speed > w.cruise * Cfg.TRAFFIC_MAX_FACTOR * 1.05f)
    }

    @Test
    fun steeringIsAnalogSmoothedAndSpeedSensitive() {
        val w = emptyWorld()
        w.x = 0f
        w.step(1, steer = 1f)
        assertTrue("smoothed, not instant", w.steer > 0f && w.steer < 0.5f)
        // analog: half input gives roughly half the lateral speed
        val a = emptyWorld(); a.x = 0f; a.step(30, steer = 0.5f)
        val b = emptyWorld(); b.x = 0f; b.step(30, steer = 1f)
        assertEquals(2f, b.x / a.x, 0.1f)
        // car can sit anywhere between lanes
        val c = emptyWorld(); c.x = 0f; c.step(120)
        assertEquals(0f, c.x, 0.3f)
        // speed sensitivity: response after 60 ms is slower and the yaw angle smaller at high speed
        assertTrue(World.latSpeed(70f) / 70f < 0.5f * World.latSpeed(25f) / 25f)
        val lowTau = Cfg.STEER_TAU_LOW; val highTau = Cfg.STEER_TAU_HIGH
        assertTrue(highTau > lowTau * 1.5f)
    }

    @Test
    fun curvesPushTheCarOutwardsGently() {
        val w = emptyWorld()
        var maxDrift = 0f
        var prev = w.x
        repeat(60 * 60) {
            w.update(dt, 0f, false, false)
            if (w.state != World.RUNNING) { w.revive() }
            maxDrift = maxOf(maxDrift, abs(w.x - prev) / dt)
            prev = w.x
            if (abs(w.x) > 2f) { w.x = 0f; prev = 0f }
        }
        assertTrue("some drift $maxDrift", maxDrift > 0.05f)
        assertTrue("bounded drift $maxDrift", maxDrift <= Cfg.DRIFT_MAX + 1e-3f)
    }

    @Test
    fun reviveClearsTheRoadAndGrantsInvulnerability() {
        val w = emptyWorld()
        w.traffic.placeForTest(Kind.BARRIER, 1, w.s + 25.0)
        w.traffic.placeForTest(Kind.BARRIER, 1, w.s + 80.0)
        w.step(400)
        assertEquals(World.OVER, w.state)
        val score = w.score
        w.revive()
        assertEquals(World.RUNNING, w.state)
        assertTrue(w.invuln > 2f)
        w.step(300)
        assertEquals(World.RUNNING, w.state)
        assertTrue(w.score > score)
    }

    @Test
    fun firstSecondsAreForgiving() {
        for (seed in 1L..200L) {
            val w = World()
            w.startRun(seed)
            var nearest = Double.MAX_VALUE
            for (i in 0 until w.traffic.count) {
                val o = w.traffic.get(i)
                nearest = minOf(nearest, o.s - w.s)
                // everything the player meets in the first ~15 s is a single moving vehicle
                if (o.s - w.s < 200) assertTrue("only moving vehicles early (seed $seed)", Kind.isVehicle(o.kind) && o.desired > 0f)
            }
            assertTrue("clear start (seed $seed): $nearest", nearest >= Cfg.START_CLEAR)
            // doing nothing at all survives the first 3 seconds, whatever the traffic does
            repeat(180) { w.update(dt, 0f, false, false) }
            assertEquals("seed $seed", World.RUNNING, w.state)
        }
    }

    @Test
    fun restartIsDeterministicAndFullyResets() {
        val a = World(); val b = World()
        a.startRun(42); a.step(600, steer = 0.2f); a.startRun(42)
        b.startRun(42)
        repeat(400) {
            a.update(dt, 0.1f, false, false); b.update(dt, 0.1f, false, false)
        }
        assertEquals(b.s, a.s, 0.0)
        assertEquals(b.x, a.x, 0f)
        assertEquals(b.traffic.count, a.traffic.count)
        assertEquals(b.score, a.score)
    }
}
