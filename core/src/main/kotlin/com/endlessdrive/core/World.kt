package com.endlessdrive.core

import com.endlessdrive.core.GameConfig.CAR_HALF_L
import com.endlessdrive.core.GameConfig.CAR_HALF_W
import com.endlessdrive.core.GameConfig.HIT_SHRINK_L
import com.endlessdrive.core.GameConfig.HIT_SHRINK_W
import com.endlessdrive.core.GameConfig.RAIL_INNER
import kotlin.math.abs

/** Gameplay callbacks (all default to no-op). Invoked on the game thread. */
interface GameEvents {
    fun onCoin(s: Double, x: Float) {}
    fun onShieldPickup(s: Double, x: Float) {}
    fun onShieldBreak(s: Double, x: Float) {}
    fun onNearMiss(s: Double, x: Float, combo: Int, points: Int, closeCall: Boolean) {}
    fun onBoost() {}
    fun onCrash(s: Double, x: Float) {}

    object None : GameEvents
}

object Collision {
    fun hits(px: Float, ps: Double, ox: Float, os: Double, oHalfW: Float, oHalfL: Float): Boolean =
        abs(px - ox) < (CAR_HALF_W + oHalfW) * HIT_SHRINK_W &&
            abs(ps - os) < (CAR_HALF_L + oHalfL) * HIT_SHRINK_L

    fun hitsRail(px: Float): Boolean = abs(px) + CAR_HALF_W * HIT_SHRINK_W > RAIL_INNER

    /** Edge-to-edge lateral gap (negative when laterally overlapping). */
    fun lateralGap(px: Float, ox: Float, oHalfW: Float): Float = abs(px - ox) - (CAR_HALF_W + oHalfW)
}

/**
 * The simulation (road frame). No Android, no allocation in update().
 */
class World(seed: Long = 1L, var events: GameEvents = GameEvents.None) {
    private val rng = Rng(seed)
    val player = PlayerCar()
    val traffic = TrafficSystem()
    val spawner = Spawner(rng)
    val pickups = Array(GameConfig.MAX_PICKUPS) { Pickup() }
    private val autoInput = Input()

    var time = 0f; private set
    var alive = true; private set
    var bonus = 0L; private set
    var coins = 0; private set
    var nearMisses = 0; private set
    var combo = 0; private set
    var comboTimer = 0f; private set
    var shieldTimer = 0f; private set
    var invulnTimer = 0f; private set
    var crashS = 0.0; private set
    var crashX = 0f; private set
    private var startS = 0.0

    /** Attract mode: autopilot steering, no collisions, no scoring. */
    var demo = false

    /** Tests can switch procedural spawning off. */
    var spawningEnabled = true

    val distance: Double get() = player.s - startS
    val score: Long get() = Scoring.total(distance, bonus)
    val baseSpeed: Float get() = Difficulty.baseSpeed(time)

    init {
        reset(seed)
    }

    fun reset(seed: Long, startTime: Float = 0f) {
        rng.setSeed(seed)
        time = startTime
        alive = true
        bonus = 0L
        coins = 0
        nearMisses = 0
        combo = 0
        comboTimer = 0f
        shieldTimer = 0f
        invulnTimer = 0f
        startS = player.s
        spawningEnabled = true
        player.reset(player.s, 1, Difficulty.baseSpeed(startTime), null)
        traffic.clear()
        for (p in pickups) p.active = false
        spawner.reset(player.s, time, traffic, pickups)
    }

    /** Continue after a crash: clears the road ahead, grants invulnerability. */
    fun revive(input: Input) {
        if (alive) return
        for (c in traffic.cars) {
            if (c.active && c.s - c.halfL < player.s + GameConfig.CONTINUE_CLEAR_AHEAD) c.active = false
        }
        val lane = if (player.x < 0f) 0 else 1
        player.reset(player.s, lane, baseSpeed * 0.8f, input)
        spawner.populate(player.s, GameConfig.CONTINUE_CLEAR_AHEAD.toDouble(), time, traffic, pickups)
        invulnTimer = GameConfig.CONTINUE_INVULN
        combo = 0
        comboTimer = 0f
        alive = true
    }

    fun update(dtIn: Float, input: Input) {
        if (!alive) return
        val dt = if (dtIn > 0.05f) 0.05f else if (dtIn < 0f) 0f else dtIn
        time += dt
        val vBase = Difficulty.baseSpeed(time)
        val inp = if (demo) autopilot() else input
        val wasBoosting = player.boosting
        player.update(dt, inp, vBase)
        if (!wasBoosting && player.boosting) events.onBoost()
        val p = player

        if (Collision.hitsRail(p.x)) {
            if (demo) {
                p.x = p.x.coerceIn(-5f, 5f)
            } else if (invulnTimer > 0f || shieldTimer > 0f) {
                if (shieldTimer > 0f) {
                    shieldTimer = 0f
                    invulnTimer = GameConfig.SHIELD_BREAK_INVULN
                    events.onShieldBreak(p.s, p.x)
                }
                val side = if (p.x > 0f) 1f else -1f
                p.x = side * (RAIL_INNER - CAR_HALF_W - 0.3f)
                p.latVel = -p.latVel * 0.3f
            } else {
                crash()
                return
            }
        }

        if (spawningEnabled) spawner.update(p.s, time, traffic, pickups)
        traffic.step(dt, p.s, p.v)

        if (shieldTimer > 0f) shieldTimer = (shieldTimer - dt).coerceAtLeast(0f)
        if (invulnTimer > 0f) invulnTimer = (invulnTimer - dt).coerceAtLeast(0f)
        if (comboTimer > 0f) {
            comboTimer -= dt
            if (comboTimer <= 0f) {
                comboTimer = 0f
                combo = 0
            }
        }

        for (i in traffic.cars.indices) {
            val c = traffic.cars[i]
            if (!c.active) continue
            if (c.s + c.halfL < p.s - GameConfig.DESPAWN_BEHIND || c.s > p.s + GameConfig.SPAWN_AHEAD + 150.0) {
                c.active = false
                continue
            }
            if (demo) continue
            if (abs(c.s - p.s) < c.halfL + CAR_HALF_L) {
                val g = Collision.lateralGap(p.x, c.x, c.halfW)
                if (g < c.minGap) c.minGap = g
            }
            if (Collision.hits(p.x, p.s, c.x, c.s, c.halfW, c.halfL)) {
                if (invulnTimer > 0f) continue
                if (shieldTimer > 0f) {
                    shieldTimer = 0f
                    invulnTimer = GameConfig.SHIELD_BREAK_INVULN
                    c.active = false
                    events.onShieldBreak(c.s, c.x)
                    continue
                }
                crash()
                return
            }
            if (!c.passed && c.s + c.halfL < p.s - CAR_HALF_L) {
                c.passed = true
                if (Scoring.isNearMiss(c.minGap, p.v)) {
                    combo = if (comboTimer > 0f) (combo + 1).coerceAtMost(GameConfig.MAX_COMBO) else 1
                    comboTimer = GameConfig.COMBO_WINDOW
                    val close = Scoring.isCloseCall(c.minGap)
                    val pts = Scoring.nearMissPoints(combo, close)
                    bonus += pts
                    nearMisses++
                    events.onNearMiss(c.s, c.x, combo, pts, close)
                }
            }
        }

        for (pk in pickups) {
            if (!pk.active) continue
            if (pk.s < p.s - GameConfig.DESPAWN_BEHIND) {
                pk.active = false
                continue
            }
            if (demo) continue
            if (abs(pk.s - p.s) < CAR_HALF_L + GameConfig.PICKUP_RADIUS &&
                abs(pk.x - p.x) < CAR_HALF_W + GameConfig.PICKUP_RADIUS
            ) {
                pk.active = false
                if (pk.kind == PickupKind.COIN) {
                    coins++
                    bonus += GameConfig.COIN_POINTS
                    events.onCoin(pk.s, pk.x)
                } else {
                    shieldTimer = GameConfig.SHIELD_TIME
                    events.onShieldPickup(pk.s, pk.x)
                }
            }
        }
    }

    /** Keeps traffic moving (slow motion) after a crash; the player is stopped. */
    fun updateAfterCrash(dt: Float) {
        traffic.step(dt, player.s, 0f)
    }

    private fun crash() {
        alive = false
        crashS = player.s
        crashX = player.x
        combo = 0
        comboTimer = 0f
        events.onCrash(player.s, player.x)
    }

    /** Demo autopilot: pick the lane whose next obstacle is farther away. */
    private fun autopilot(): Input {
        val p = player
        var near0 = Double.MAX_VALUE
        var near1 = Double.MAX_VALUE
        for (c in traffic.cars) {
            if (!c.active || c.s + c.halfL < p.s - CAR_HALF_L) continue
            val d = c.s - c.halfL - p.s
            if (c.lane == 0 && d < near0) near0 = d
            if (c.lane == 1 && d < near1) near1 = d
        }
        val targetLane = if (near0 > near1 + 5.0) 0 else if (near1 > near0 + 5.0) 1 else (if (p.x < 0f) 0 else 1)
        val tx = GameConfig.laneX(targetLane)
        autoInput.steer = ((tx - p.x) * 0.7f - p.latVel * 0.15f).coerceIn(-1f, 1f)
        autoInput.brake = false
        return autoInput
    }

    // ---- test helpers ----
    fun placeForTest(kind: Int, lane: Int, sAhead: Double, speed: Float = 0f): TrafficCar {
        val c = traffic.alloc()!!
        c.active = true
        c.kind = kind
        c.lane = lane
        c.x = GameConfig.laneX(lane)
        c.s = player.s + sAhead
        c.halfW = Kind.HALF_W[kind]
        c.halfL = Kind.HALF_L[kind]
        c.desired = speed
        c.speed = speed
        c.passed = false
        c.minGap = Float.MAX_VALUE
        return c
    }

    fun placePickupForTest(kind: Int, x: Float, sAhead: Double) {
        for (pk in pickups) if (!pk.active) {
            pk.active = true; pk.kind = kind; pk.x = x; pk.s = player.s + sAhead
            return
        }
    }

    fun clearTrafficForTest() {
        traffic.clear()
        for (pk in pickups) pk.active = false
    }

    /** Pushes procedural spawning far into the future (tests). */
    fun suppressSpawningForTest() {
        clearTrafficForTest()
        spawningEnabled = false
    }
}
