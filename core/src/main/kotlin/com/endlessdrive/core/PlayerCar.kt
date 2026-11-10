package com.endlessdrive.core

import com.endlessdrive.core.GameConfig.ACCEL
import com.endlessdrive.core.GameConfig.BOOST_COOLDOWN
import com.endlessdrive.core.GameConfig.BOOST_MULT
import com.endlessdrive.core.GameConfig.BOOST_TIME
import com.endlessdrive.core.GameConfig.BRAKE_DECEL
import com.endlessdrive.core.GameConfig.BRAKE_MIN_SPEED
import com.endlessdrive.core.GameConfig.LAT_MAX
import com.endlessdrive.core.GameConfig.ROAD_HALF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Player input written by the UI thread, read by the game thread (plain fields; tearing is harmless). */
class Input {
    @Volatile @JvmField var steer = 0f // -1 .. 1
    @Volatile @JvmField var brake = false
    @Volatile @JvmField var boostPresses = 0 // monotonically increasing press counter
}

/**
 * Arcade car in the road frame. Throttle is automatic; steering is analog and continuous:
 * the stick sets a target lateral speed, approached with a first-order lag whose time constant
 * (and an input expo curve) grow with speed -> calmer steering at high speed, same max authority.
 */
class PlayerCar {
    var s = 0.0
    var x = GameConfig.LANE1_X
    var v = GameConfig.V0
    var latVel = 0f
    var boostTimer = 0f
    var boostCooldown = 0f
    var braking = false
    var onShoulder = false
    private var lastBoostPresses = 0

    val boosting: Boolean get() = boostTimer > 0f
    /** 1 = boost ready, otherwise recharge progress 0..1. */
    val boostReady: Float get() = if (boostCooldown <= 0f) 1f else 1f - boostCooldown / BOOST_COOLDOWN
    /** Visual yaw relative to the road (radians). */
    val yaw: Float get() = atan2(latVel, max(1f, v))

    fun reset(s0: Double, lane: Int, v0: Float, input: Input?) {
        s = s0
        x = GameConfig.laneX(lane)
        v = v0
        latVel = 0f
        boostTimer = 0f
        boostCooldown = 0f
        braking = false
        onShoulder = false
        lastBoostPresses = input?.boostPresses ?: 0
    }

    fun speedNorm(): Float =
        ((v - GameConfig.V0) / (GameConfig.V_MAX_PLAYER - GameConfig.V0)).coerceIn(0f, 1f)

    fun update(dt: Float, input: Input, vBase: Float) {
        val sn = speedNorm()
        // --- steering ---
        val raw = input.steer.coerceIn(-1f, 1f)
        val expo = 1f + GameConfig.STEER_EXPO_FAST * sn
        val shaped = if (raw >= 0f) raw.pow(expo) else -(-raw).pow(expo)
        val tau = GameConfig.STEER_TAU_SLOW + (GameConfig.STEER_TAU_FAST - GameConfig.STEER_TAU_SLOW) * sn
        val target = shaped * LAT_MAX
        latVel += (target - latVel) * (1f - exp(-dt / tau))
        x += latVel * dt
        onShoulder = abs(x) > ROAD_HALF

        // --- boost ---
        val presses = input.boostPresses
        if (presses != lastBoostPresses) {
            lastBoostPresses = presses
            if (boostCooldown <= 0f) {
                boostTimer = BOOST_TIME
                boostCooldown = BOOST_COOLDOWN
            }
        }
        if (boostTimer > 0f) boostTimer = max(0f, boostTimer - dt)
        if (boostCooldown > 0f) boostCooldown = max(0f, boostCooldown - dt)

        // --- longitudinal ---
        braking = input.brake
        var targetV = vBase * (if (boostTimer > 0f) BOOST_MULT else 1f)
        if (braking) targetV = BRAKE_MIN_SPEED
        if (onShoulder) targetV = min(targetV, vBase * GameConfig.SHOULDER_SPEED_FACTOR)
        v = if (v < targetV) {
            min(targetV, v + ACCEL * (if (boostTimer > 0f) 2.5f else 1f) * dt)
        } else {
            val decel = when {
                braking -> BRAKE_DECEL
                onShoulder -> GameConfig.SHOULDER_DECEL
                else -> GameConfig.COAST_DECEL
            }
            max(targetV, v - decel * dt)
        }
        v = min(v, GameConfig.V_MAX_PLAYER)
        s += v * dt
    }
}
