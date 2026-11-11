package com.endlessdrive.core

import com.endlessdrive.core.fx.ParticleSystem
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp

class Pickup {
    var kind = COIN
    var s = 0.0
    var x = 0f
    var active = false

    companion object {
        const val COIN = 0
        const val SHIELD = 1
    }
}

/**
 * The whole simulation. Pure Kotlin, no rendering or platform code, no allocation in update().
 * Player position is (s, x) in road space: s = arc length along the centreline, x = lateral offset.
 */
class World : ClusterListener {
    val road = Road()
    val traffic = Traffic()
    val pickups = Array(64) { Pickup() }
    val particles = ParticleSystem(256)
    val env = EnvCycle()
    val combo = Combo()
    private val rng = Rng(7)
    private val fxRng = Rng(99)

    var state = ATTRACT
        private set
    var seed = 1L
        private set
    var time = 0f
        private set
    var s = Cfg.START_S
        private set
    var x = Cfg.LANE_RIGHT_X
    var speed = 0f
        private set
    var cruise = Cfg.V0
        private set
    /** Smoothed steering in [-1, 1]. */
    var steer = 0f
        private set
    /** Visual yaw of the car relative to the road (radians, + = right). */
    var yaw = 0f
        private set
    var boostCharge = 1f
        private set
    var boostTime = 0f
        private set
    var shieldTime = 0f
        private set
    var invuln = 0f
        private set
    var onShoulder = false
        private set
    var braking = false
        private set
    var crashTimer = 0f
        private set
    var spin = 0f
        private set

    var bonus = 0
        private set
    var coins = 0
        private set
    var nearMisses = 0
        private set
    val distance: Float get() = (s - Cfg.START_S).toFloat()
    val score: Int get() = distance.toInt() + bonus

    /** Bitmask of Ev.* raised during the last update(). */
    var events = 0
        private set
    var lastNearMissPoints = 0
        private set
    /** Seconds since the last near miss (for the HUD popup). */
    var sinceNearMiss = 99f
        private set

    private var shieldCountdown = 20f
    private var dustAcc = 0f
    private var flameAcc = 0f
    private var envOffset = 0f
    val boosting: Boolean get() = boostTime > 0f

    init {
        traffic.listener = this
        enterAttract(1L)
    }

    /** Menu backdrop: an empty road with the car cruising. */
    fun enterAttract(seed: Long) {
        resetCommon(seed)
        state = ATTRACT
        traffic.spawningEnabled = false
        traffic.clearAll()
        speed = 20f
    }

    fun startRun(seed: Long) {
        resetCommon(seed)
        state = RUNNING
        traffic.spawningEnabled = true
        traffic.reset(seed xor 0x5DEECE66DL, s)
        speed = Cfg.V0 * 0.75f
    }

    private fun resetCommon(seed: Long) {
        this.seed = seed
        rng.setSeed(seed)
        road.reset(seed)
        for (p in pickups) p.active = false
        particles.clear()
        combo.reset()
        time = 0f
        s = Cfg.START_S
        x = Cfg.LANE_RIGHT_X
        steer = 0f; yaw = 0f
        cruise = Cfg.V0
        boostCharge = 1f; boostTime = 0f
        shieldTime = 0f; invuln = 0f
        onShoulder = false; braking = false
        crashTimer = 0f; spin = 0f
        bonus = 0; coins = 0; nearMisses = 0
        events = 0
        lastNearMissPoints = 0; sinceNearMiss = 99f
        shieldCountdown = rng.range(16f, 24f)
        envOffset = 0f
        env.update(0f)
        road.ensure(s + 700.0)
        // traffic is (re)filled by the caller after this
        traffic.spawningEnabled = false
        traffic.reset(seed, s + 1.0e7) // empties the queue; nothing lands near the player
    }

    /** Continue after a crash (rewarded ad): clear the road ahead and grant brief invulnerability. */
    fun revive() {
        if (state != OVER && state != CRASHING) return
        state = RUNNING
        traffic.clearRange(s - 40.0, s + 170.0)
        invuln = Cfg.INVULN_AFTER_REVIVE
        shieldTime = 0f
        speed = cruise * 0.7f
        if (speed < cruise * Cfg.SLOW_FACTOR) speed = cruise * Cfg.SLOW_FACTOR
        val lim = Cfg.ROAD_HALF - Cfg.PLAYER_HALF_W
        x = if (x < 0f) Cfg.LANE_LEFT_X else Cfg.LANE_RIGHT_X
        if (x > lim) x = lim
        steer = 0f; yaw = 0f; spin = 0f; crashTimer = 0f
        combo.breakCombo()
    }

    /**
     * Advances the simulation by [dtReal] seconds.
     * @param steerInput analog steering in [-1, 1]
     */
    fun update(dtReal: Float, steerInput: Float, brake: Boolean, nitro: Boolean) {
        events = 0
        when (state) {
            RUNNING -> updateRunning(dtReal, steerInput, brake, nitro)
            CRASHING -> updateCrashing(dtReal)
            ATTRACT -> updateAttract(dtReal)
            else -> particles.update(dtReal * Cfg.CRASH_TIME_SCALE)
        }
    }

    private fun updateAttract(dt: Float) {
        time += dt
        s += speed * dt
        val want = Cfg.LANE_RIGHT_X
        x += (want - x) * (1f - exp(-dt * 2f))
        road.ensure(s + 700.0)
        env.update(0f)
    }

    private fun updateRunning(dt: Float, steerInput: Float, brake: Boolean, nitro: Boolean) {
        time += dt
        cruise = Difficulty.cruise(time)
        env.update(time + envOffset)
        braking = brake
        sinceNearMiss += dt

        // --- nitro ---
        if (boostTime > 0f) {
            boostTime -= dt
            if (boostTime < 0f) boostTime = 0f
            boostCharge = boostTime / Cfg.NITRO_DURATION
        } else if (boostCharge < 1f) {
            boostCharge += dt / Cfg.NITRO_COOLDOWN
            if (boostCharge >= 1f) {
                boostCharge = 1f
                events = events or Ev.NITRO_READY
            }
        }
        onShoulder = Collision.onShoulder(x)
        if (nitro && boostCharge >= 1f && boostTime <= 0f && !onShoulder) {
            boostTime = Cfg.NITRO_DURATION
            events = events or Ev.NITRO
        }

        // --- longitudinal speed ---
        val floor = cruise * Cfg.SLOW_FACTOR
        var target = cruise
        if (boostTime > 0f) target *= Cfg.NITRO_MULT
        if (onShoulder || brake) target = floor
        if (speed < target) {
            speed += (if (boostTime > 0f) Cfg.ACCEL_NITRO else Cfg.ACCEL) * dt
            if (speed > target) speed = target
        } else if (speed > target) {
            speed -= (if (brake) Cfg.DECEL_BRAKE else if (onShoulder) Cfg.DECEL_SHOULDER else Cfg.DECEL_COAST) * dt
            if (speed < target) speed = target
        }
        if (speed < floor) speed = floor

        // --- steering: smoothed, speed-sensitive ---
        val sn = speedNorm()
        val tau = Cfg.STEER_TAU_LOW + (Cfg.STEER_TAU_HIGH - Cfg.STEER_TAU_LOW) * sn
        val inp = if (steerInput < -1f) -1f else if (steerInput > 1f) 1f else steerInput
        steer += (inp - steer) * (1f - exp(-dt / tau))
        val lat = steer * latSpeed(speed)
        val curv = road.curvature(s)
        var drift = -Cfg.DRIFT_K * curv * speed * speed
        if (drift > Cfg.DRIFT_MAX) drift = Cfg.DRIFT_MAX
        if (drift < -Cfg.DRIFT_MAX) drift = -Cfg.DRIFT_MAX
        x += (lat + drift) * dt
        yaw = atan2(lat, speed)
        s += speed * dt
        road.ensure(s + 700.0)

        combo.update(dt)
        if (shieldTime > 0f) { shieldTime -= dt; if (shieldTime < 0f) shieldTime = 0f }
        if (invuln > 0f) { invuln -= dt; if (invuln < 0f) invuln = 0f }
        shieldCountdown -= dt

        traffic.update(dt, s, time)

        // --- guard rails ---
        if (Collision.hitsRail(x)) {
            val lim = Cfg.RAIL_X - Cfg.PLAYER_HALF_W
            if (shieldTime > 0f || invuln > 0f) {
                x = if (x > 0f) lim - 0.4f else -lim + 0.4f
                steer = 0f
                if (shieldTime > 0f) breakShield()
            } else {
                x = if (x > 0f) lim else -lim
                crash()
                return
            }
        }

        // --- traffic: collisions and near misses ---
        val n = traffic.count
        var i = 0
        while (i < n) {
            val o = traffic.get(i)
            i++
            if (!o.alive) continue
            val ds = (o.s - s).toFloat()
            if (ds > 40f) continue
            val dx = o.x - x
            if (Collision.overlaps(ds, dx, o.halfL, o.halfW)) {
                if (invuln > 0f) continue
                if (shieldTime > 0f) {
                    o.alive = false
                    burst(o.s, o.x, 14, 1f, 0.75f, 0.3f)
                    breakShield()
                    continue
                }
                crash()
                return
            }
            if (!o.passed && ds < 0f) {
                o.passed = true
                if (Kind.isVehicle(o.kind) && o.lane >= 0 &&
                    Collision.lateralGap(dx, o.halfW) < Cfg.NEAR_MISS_DIST &&
                    speed - o.speed >= Cfg.NEAR_MISS_MIN_REL_SPEED
                ) {
                    val pts = combo.onNearMiss()
                    bonus += pts
                    nearMisses++
                    lastNearMissPoints = pts
                    sinceNearMiss = 0f
                    events = events or Ev.NEAR_MISS
                }
            }
        }

        // --- pickups ---
        for (p in pickups) {
            if (!p.active) continue
            val ds = (p.s - s).toFloat()
            if (ds < -Cfg.DESPAWN_BEHIND) { p.active = false; continue }
            if (ds > -2.8f && ds < 2.8f && abs(p.x - x) < 1.5f) {
                p.active = false
                if (p.kind == Pickup.COIN) {
                    coins++
                    bonus += Cfg.COIN_POINTS
                    events = events or Ev.COIN
                    sparkle(p.s, p.x, 1f, 0.85f, 0.2f)
                } else {
                    shieldTime = Cfg.SHIELD_DURATION
                    events = events or Ev.SHIELD
                    sparkle(p.s, p.x, 0.3f, 0.7f, 1f)
                }
            }
        }

        // --- ambient particles ---
        if (onShoulder) {
            dustAcc += dt * 40f
            while (dustAcc >= 1f) {
                dustAcc -= 1f
                val side = if (x > 0f) 1f else -1f
                particles.emit(
                    s - 1.6, x + side * 0.7f + fxRng.range(-0.3f, 0.3f), 0.15f,
                    speed * 0.55f, fxRng.range(-1f, 1f), fxRng.range(0.5f, 2.2f),
                    fxRng.range(0.4f, 0.8f), fxRng.range(0.35f, 0.7f), 0.62f, 0.55f, 0.45f, 1.5f, false,
                )
            }
        }
        if (boostTime > 0f) {
            flameAcc += dt * 70f
            while (flameAcc >= 1f) {
                flameAcc -= 1f
                val side = if (fxRng.chance(0.5f)) -0.45f else 0.45f
                particles.emit(
                    s - 2.2, x + side, 0.4f, speed - fxRng.range(6f, 14f), fxRng.range(-0.4f, 0.4f), fxRng.range(-0.2f, 0.6f),
                    fxRng.range(0.12f, 0.28f), fxRng.range(0.2f, 0.4f), 0.4f, 0.75f, 1f, 0f, true,
                )
            }
        }
        particles.update(dt)
    }

    private fun updateCrashing(dtReal: Float) {
        crashTimer += dtReal
        val dt = dtReal * Cfg.CRASH_TIME_SCALE
        speed *= exp(-dt * 6f)
        s += speed * dt
        spin += dt * 9f * exp(-crashTimer * 1.2f)
        road.ensure(s + 700.0)
        traffic.spawningEnabled = false
        traffic.update(dt, s, time)
        traffic.spawningEnabled = true
        particles.update(dt)
        if (crashTimer >= Cfg.CRASH_DURATION) {
            state = OVER
            events = events or Ev.GAME_OVER
        }
    }

    private fun crash() {
        state = CRASHING
        crashTimer = 0f
        boostTime = 0f
        combo.breakCombo()
        events = events or Ev.CRASH
        burst(s + 1.5, x, 46, 1f, 0.6f, 0.15f)
        for (k in 0 until 18) { // dark debris
            particles.emit(
                s + 1.0, x + fxRng.range(-0.8f, 0.8f), 0.6f,
                speed * fxRng.range(0.3f, 0.9f), fxRng.range(-6f, 6f), fxRng.range(2f, 9f),
                fxRng.range(1.2f, 2.5f), fxRng.range(0.12f, 0.3f), 0.15f, 0.15f, 0.17f, 12f, false,
            )
        }
    }

    private fun breakShield() {
        shieldTime = 0f
        invuln = Cfg.INVULN_AFTER_SHIELD
        events = events or Ev.SHIELD_BREAK
        burst(s, x, 20, 0.35f, 0.75f, 1f)
    }

    private fun burst(ps: Double, px: Float, n: Int, r: Float, g: Float, b: Float) {
        for (k in 0 until n) {
            particles.emit(
                ps, px + fxRng.range(-0.6f, 0.6f), fxRng.range(0.3f, 1.2f),
                speed * fxRng.range(0.2f, 1.0f), fxRng.range(-8f, 8f), fxRng.range(1f, 10f),
                fxRng.range(0.5f, 1.4f), fxRng.range(0.15f, 0.45f), r, g * fxRng.range(0.6f, 1f), b, 9f, true,
            )
        }
    }

    private fun sparkle(ps: Double, px: Float, r: Float, g: Float, b: Float) {
        for (k in 0 until 8) {
            particles.emit(
                ps + 2.0, px + fxRng.range(-0.4f, 0.4f), fxRng.range(0.5f, 1.3f),
                speed + fxRng.range(-3f, 3f), fxRng.range(-3f, 3f), fxRng.range(1f, 5f),
                fxRng.range(0.3f, 0.6f), fxRng.range(0.12f, 0.25f), r, g, b, 6f, true,
            )
        }
    }

    /** Reward the free lane next to each cluster with coins (or, periodically, a shield). */
    override fun onCluster(lane: Int, s0: Double, s1: Double, moving: Boolean) {
        val other = if (lane == 0) Cfg.LANE_RIGHT_X else Cfg.LANE_LEFT_X
        if (shieldCountdown <= 0f) {
            shieldCountdown = rng.range(24f, 38f)
            addPickup(Pickup.SHIELD, s0 + 4.0, other)
            return
        }
        if (!rng.chance(0.7f)) return
        val n = 4 + rng.nextInt(3)
        for (k in 0 until n) addPickup(Pickup.COIN, s0 + k * 7.0, other)
    }

    private fun addPickup(kind: Int, ps: Double, px: Float) {
        for (p in pickups) {
            if (!p.active) {
                p.kind = kind; p.s = ps; p.x = px; p.active = true
                return
            }
        }
    }

    /** 0 at V0, 1 at absolute top (nitro at cap) speed. */
    fun speedNorm(): Float {
        val t = (speed - Cfg.V0) / (Cfg.VMAX * Cfg.NITRO_MULT - Cfg.V0)
        return if (t < 0f) 0f else if (t > 1f) 1f else t
    }

    fun forceEnvTime(t: Float) {
        envOffset = t - time
        env.update(t)
    }

    companion object {
        const val ATTRACT = 0
        const val RUNNING = 1
        const val CRASHING = 2
        const val OVER = 3

        /** Max lateral speed grows only slightly with speed, so the steering angle shrinks as speed rises. */
        fun latSpeed(v: Float): Float {
            var t = (v - Cfg.V0) / (Cfg.VMAX * Cfg.NITRO_MULT - Cfg.V0)
            if (t < 0f) t = 0f
            if (t > 1f) t = 1f
            return Cfg.LAT_SPEED_LOW + (Cfg.LAT_SPEED_HIGH - Cfg.LAT_SPEED_LOW) * t
        }
    }
}
