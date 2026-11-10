package com.endlessdrive.core

import com.endlessdrive.core.GameConfig.GAP_OTHER_LANE
import com.endlessdrive.core.GameConfig.GAP_SAME_LANE
import kotlin.math.max
import kotlin.math.min

class TrafficCar {
    @JvmField var active = false
    @JvmField var kind = 0
    /** -1 left shoulder, 0 left lane, 1 right lane, 2 right shoulder. */
    @JvmField var lane = 0
    @JvmField var x = 0f
    @JvmField var s = 0.0
    @JvmField var speed = 0f
    @JvmField var desired = 0f
    @JvmField var halfW = 0f
    @JvmField var halfL = 0f
    @JvmField var passed = false
    @JvmField var minGap = Float.MAX_VALUE
    @JvmField var variant = 0
    /** Visual-only: brake lights when the AI is holding back. */
    @JvmField var braking = false
}

/**
 * Pool of traffic plus the AI. The AI never changes lanes; it only regulates speed so that two
 * invariants hold at every tick (proved per step, verified by tests):
 *   (I1) objects in the SAME lane keep >= GAP_SAME_LANE edge-to-edge headway;
 *   (I2) objects in DIFFERENT lanes keep >= GAP_OTHER_LANE edge-to-edge separation along s.
 * A follower's new speed is capped by  v_front + (gap − required)/dt , processed front-to-back,
 * so the gap after the step is never below the requirement. Cars behind the player never go
 * faster than the player (no rear-ending the player).
 */
class TrafficSystem(capacity: Int = GameConfig.MAX_TRAFFIC) {
    val cars = Array(capacity) { TrafficCar() }
    private val order = IntArray(capacity)
    private var orderCount = 0

    fun clear() {
        for (c in cars) c.active = false
    }

    fun activeCount(): Int {
        var n = 0
        for (c in cars) if (c.active) n++
        return n
    }

    fun alloc(): TrafficCar? {
        for (c in cars) if (!c.active) return c
        return null
    }

    /** Edge-to-edge gap along s between two objects (negative = overlapping). */
    fun gap(a: TrafficCar, s: Double, halfL: Float): Double =
        if (a.s >= s) (a.s - a.halfL) - (s + halfL) else (s - halfL) - (a.s + a.halfL)

    /** True if an object at (lane, s, halfL) respects I1/I2 against every lane object. */
    fun placementOk(lane: Int, s: Double, halfL: Float): Boolean {
        if (lane == 0 || lane == 1) {
            for (o in cars) {
                if (!o.active || !Kind.isLaneObject(o.kind)) continue
                val req = if (o.lane == lane) GAP_SAME_LANE else GAP_OTHER_LANE
                if (gap(o, s, halfL) < req) return false
            }
        } else {
            for (o in cars) {
                if (!o.active || o.lane != lane) continue
                if (gap(o, s, halfL) < GameConfig.SHOULDER_SPACING) return false
            }
        }
        return true
    }

    fun step(dt: Float, playerS: Double, playerV: Float) {
        if (dt <= 0f) return
        // Sort active lane objects by s descending (insertion sort; nearly sorted -> ~O(n)).
        orderCount = 0
        for (i in cars.indices) {
            val c = cars[i]
            if (!c.active) continue
            if (!Kind.isLaneObject(c.kind)) continue
            var j = orderCount++
            while (j > 0 && cars[order[j - 1]].s < c.s) {
                order[j] = order[j - 1]
                j--
            }
            order[j] = i
        }
        for (k in 0 until orderCount) {
            val c = cars[order[k]]
            var lim = c.desired
            if (c.s < playerS + 2.0) lim = min(lim, playerV)
            for (m in 0 until k) {
                val o = cars[order[m]]
                if (o.s <= c.s) continue
                val g = (o.s - o.halfL) - (c.s + c.halfL)
                val req = if (o.lane == c.lane) GAP_SAME_LANE else GAP_OTHER_LANE
                val allowed = o.speed + ((g - req) / dt).toFloat()
                if (allowed < lim) lim = allowed
            }
            val newSpeed = max(0f, min(lim, c.desired))
            c.braking = c.desired > 0f && newSpeed < c.desired - 0.5f
            c.speed = newSpeed
        }
        for (k in 0 until orderCount) {
            val c = cars[order[k]]
            c.s += c.speed * dt
        }
    }

    /** Test/diagnostic helper: smallest violation margin of I1/I2 (>= 0 means invariants hold). */
    fun invariantMargin(): Double {
        var worst = Double.MAX_VALUE
        for (i in cars.indices) {
            val a = cars[i]
            if (!a.active || !Kind.isLaneObject(a.kind)) continue
            for (j in i + 1 until cars.size) {
                val b = cars[j]
                if (!b.active || !Kind.isLaneObject(b.kind)) continue
                val g = gap(a, b.s, b.halfL)
                val req = if (a.lane == b.lane) GAP_SAME_LANE else GAP_OTHER_LANE
                worst = min(worst, g - req)
            }
        }
        return worst
    }
}
