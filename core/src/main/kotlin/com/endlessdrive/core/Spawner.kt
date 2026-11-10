package com.endlessdrive.core

import com.endlessdrive.core.GameConfig.SPAWN_AHEAD
import kotlin.math.abs

/**
 * Spawns traffic far ahead (beyond the maximum draw distance) every `spacing(t)` metres of player
 * travel. A candidate is accepted only if it satisfies the traffic invariants (I1/I2) against all
 * existing objects; otherwise the other lane is tried, then the event is skipped. Together with
 * the AI's speed regulation this keeps a drivable path open at all times (see DECISIONS.md §4).
 */
class Spawner(private val rng: Rng) {
    var nextSpawnAt = 0.0
        private set
    private var nextCoinAt = 0.0
    private var nextShieldTime = GameConfig.FIRST_SHIELD_TIME
    var skipped = 0
        private set
    var spawned = 0
        private set

    fun reset(playerS: Double, time: Float, traffic: TrafficSystem, pickups: Array<Pickup>) {
        skipped = 0
        spawned = 0
        nextShieldTime = time + GameConfig.FIRST_SHIELD_TIME
        nextCoinAt = playerS + 60.0
        populate(playerS, GameConfig.PREPOPULATE_FROM.toDouble(), time, traffic, pickups)
    }

    /** Fills [playerS + from, playerS + SPAWN_AHEAD] with traffic (run start / continue). */
    fun populate(playerS: Double, from: Double, time: Float, traffic: TrafficSystem, pickups: Array<Pickup>) {
        var s = playerS + from
        val vBase = Difficulty.baseSpeed(time)
        while (s < playerS + SPAWN_AHEAD) {
            trySpawn(s, time, vBase, traffic, pickups)
            s += Difficulty.spacing(time) * rng.range(0.7f, 1.3f)
        }
        nextSpawnAt = playerS + Difficulty.spacing(time) * rng.range(0.7f, 1.3f)
    }

    fun update(playerS: Double, time: Float, traffic: TrafficSystem, pickups: Array<Pickup>) {
        val vBase = Difficulty.baseSpeed(time)
        while (playerS >= nextSpawnAt) {
            trySpawn(playerS + SPAWN_AHEAD, time, vBase, traffic, pickups)
            nextSpawnAt += Difficulty.spacing(time) * rng.range(0.7f, 1.3f)
        }
        if (playerS >= nextCoinAt) {
            spawnCoins(playerS + SPAWN_AHEAD, traffic, pickups)
            nextCoinAt = playerS + rng.range(140f, 260f)
        }
        if (time >= nextShieldTime) {
            spawnShield(playerS + SPAWN_AHEAD - 20.0, traffic, pickups)
            nextShieldTime = time + rng.range(GameConfig.SHIELD_MIN_INTERVAL, GameConfig.SHIELD_MAX_INTERVAL)
        }
    }

    private fun trySpawn(s: Double, time: Float, vBase: Float, traffic: TrafficSystem, pickups: Array<Pickup>) {
        val level = Difficulty.level(time)
        val grace = Difficulty.inGrace(time)
        val kind = Difficulty.pickKind(level, grace, rng.nextFloat())
        val halfL = if (kind == Kind.ROADWORKS) rng.range(18f, 32f) else Kind.HALF_L[kind]
        val first = rng.nextInt(2)
        var placed = false
        for (attempt in 0 until 2) {
            val lane = if (attempt == 0) first else 1 - first
            if (traffic.placementOk(lane, s, halfL)) {
                place(kind, lane, s, halfL, vBase, traffic)
                placed = true
                break
            }
        }
        if (placed) spawned++ else skipped++
        // Independent roadside hazard: a broken-down car on a shoulder.
        if (!grace && rng.chance(0.12f)) {
            val lane = if (rng.chance(0.5f)) -1 else 2
            val ss = s + rng.range(-30f, 30f)
            if (traffic.placementOk(lane, ss, Kind.HALF_L[Kind.SHOULDER_CAR])) {
                place(Kind.SHOULDER_CAR, lane, ss, Kind.HALF_L[Kind.SHOULDER_CAR], vBase, traffic)
            }
        }
    }

    private fun place(kind: Int, lane: Int, s: Double, halfL: Float, vBase: Float, traffic: TrafficSystem) {
        val c = traffic.alloc() ?: return
        c.active = true
        c.kind = kind
        c.lane = lane
        c.s = s
        c.halfL = halfL
        c.halfW = Kind.HALF_W[kind]
        val jitter = if (Kind.MOVING[kind] || kind == Kind.STOPPED_CAR) rng.range(-0.25f, 0.25f) else 0f
        c.x = GameConfig.laneX(lane) + jitter
        c.desired = when (kind) {
            Kind.CAR -> vBase * rng.range(0.45f, 0.70f) + (if (lane == 0) 1.5f else 0f)
            Kind.TRUCK -> vBase * rng.range(0.38f, 0.52f)
            else -> 0f
        }
        c.speed = c.desired
        c.passed = false
        c.minGap = Float.MAX_VALUE
        c.variant = rng.nextInt(16)
        c.braking = false
    }

    private fun laneClear(lane: Int, s: Double, radius: Double, traffic: TrafficSystem): Boolean {
        for (o in traffic.cars) {
            if (o.active && o.lane == lane && abs(o.s - s) < radius + o.halfL) return false
        }
        return true
    }

    private fun spawnCoins(s: Double, traffic: TrafficSystem, pickups: Array<Pickup>) {
        val lane = rng.nextInt(2)
        val l = if (laneClear(lane, s, 45.0, traffic)) lane else if (laneClear(1 - lane, s, 45.0, traffic)) 1 - lane else return
        for (i in 0 until 6) addPickup(PickupKind.COIN, GameConfig.laneX(l), s + i * 7.0, pickups)
    }

    private fun spawnShield(s: Double, traffic: TrafficSystem, pickups: Array<Pickup>) {
        val lane = rng.nextInt(2)
        val l = if (laneClear(lane, s, 30.0, traffic)) lane else 1 - lane
        addPickup(PickupKind.SHIELD, GameConfig.laneX(l), s, pickups)
    }

    private fun addPickup(kind: Int, x: Float, s: Double, pickups: Array<Pickup>) {
        for (p in pickups) {
            if (p.active) continue
            p.active = true
            p.kind = kind
            p.x = x
            p.s = s
            return
        }
    }
}
