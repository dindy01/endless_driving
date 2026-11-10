package com.endlessdrive.render

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Pose of the road centreline at some s (world coordinates in doubles for precision). */
class RoadPose {
    @JvmField var x = 0.0
    @JvmField var y = 0.0
    @JvmField var z = 0.0
    @JvmField var heading = 0f
    @JvmField var grade = 0f
    /** Forward unit vector (horizontal) and right unit vector. */
    val fx: Float get() = sin(heading)
    val fz: Float get() = cos(heading)
    val rx: Float get() = -cos(heading)
    val rz: Float get() = sin(heading)
}

/**
 * Endless procedural centreline: curvature and height are smooth sums of sines with seeded
 * phases (gentle curves, small hills). Sampled every [STEP] m into a ring buffer that is extended
 * ahead and recycled behind, so memory is constant. Gameplay never reads this (road frame).
 */
class RoadGeometry(seed: Long = 99L, private val capacity: Int = 2048) {
    private val px = DoubleArray(capacity)
    private val pz = DoubleArray(capacity)
    private val py = FloatArray(capacity)
    private val ph = FloatArray(capacity)
    private var firstIndex = 0L // global sample index of the oldest stored sample
    private var count = 0
    private val p1: Double
    private val p2: Double
    private val p3: Double
    private val p4: Double
    private val p5: Double

    init {
        val r = java.util.Random(seed)
        p1 = r.nextDouble() * 6.28; p2 = r.nextDouble() * 6.28; p3 = r.nextDouble() * 6.28
        p4 = r.nextDouble() * 6.28; p5 = r.nextDouble() * 6.28
    }

    fun curvature(s: Double): Double =
        0.0016 * sin(s / 900.0 * 6.2832 + p1) + 0.0011 * sin(s / 1700.0 * 6.2832 + p2) + 0.0006 * sin(s / 520.0 * 6.2832 + p3)

    fun height(s: Double): Double = 8.0 * sin(s / 1200.0 * 6.2832 + p4) + 3.0 * sin(s / 560.0 * 6.2832 + p5)

    val firstS: Double get() = firstIndex * STEP
    val lastS: Double get() = (firstIndex + count - 1) * STEP

    /** Starts the buffer so that it covers [s0 - 50, ...]. */
    fun reset(s0: Double) {
        firstIndex = floor((s0 - 50.0) / STEP).toLong()
        count = 1
        px[0] = 0.0; pz[0] = 0.0
        py[0] = height(firstIndex * STEP).toFloat()
        ph[0] = 0f
    }

    /** Extends the buffer to cover sMax, recycling samples older than sMin. */
    fun ensure(sMin: Double, sMax: Double) {
        if (count == 0) reset(sMin)
        while (lastS < sMax) {
            val i = ((firstIndex + count - 1) .mod(capacity.toLong())).toInt()
            val s = lastS
            val hMid = ph[i] + (curvature(s + STEP / 2) * STEP / 2).toFloat()
            val hNew = ph[i] + (curvature(s + STEP / 2) * STEP).toFloat()
            if (count == capacity) {
                firstIndex++
                count--
            }
            val j = ((firstIndex + count) .mod(capacity.toLong())).toInt()
            px[j] = px[i] + sin(hMid.toDouble()) * STEP
            pz[j] = pz[i] + cos(hMid.toDouble()) * STEP
            py[j] = height(s + STEP).toFloat()
            ph[j] = hNew
            count++
        }
        // Drop samples far behind (keeps ring from filling with stale data).
        while (count > 2 && (firstIndex + 1) * STEP < sMin - 60.0) {
            firstIndex++
            count--
        }
    }

    fun covers(s: Double): Boolean = s >= firstS && s <= lastS

    /** Interpolated pose at s (clamped to the stored window). */
    fun pose(s: Double, out: RoadPose) {
        var t = (s / STEP) - firstIndex
        if (t < 0) t = 0.0
        if (t > count - 1.001) t = count - 1.001
        val k = floor(t).toLong()
        val f = (t - k)
        val a = ((firstIndex + k) .mod(capacity.toLong())).toInt()
        val b = ((firstIndex + k + 1) .mod(capacity.toLong())).toInt()
        out.x = px[a] + (px[b] - px[a]) * f
        out.z = pz[a] + (pz[b] - pz[a]) * f
        out.y = py[a] + (py[b] - py[a]) * f
        out.heading = (ph[a] + (ph[b] - ph[a]) * f).toFloat()
        out.grade = ((py[b] - py[a]) / STEP).toFloat()
    }

    companion object {
        const val STEP = 2.0
    }
}
