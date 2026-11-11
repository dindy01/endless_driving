package com.endlessdrive.core

import kotlin.math.cos
import kotlin.math.sin

class RoadFrame {
    var x = 0.0
    var z = 0.0
    var y = 0f
    var heading = 0f
    var slope = 0f
}

/**
 * Endless procedural road. Heading and height are analytic functions of arc length s (sums of
 * sines, deterministic per seed), so any stretch can be regenerated; centreline positions are
 * integrated incrementally into a ring buffer that streams ahead of the player.
 *
 * World axes: +y up, heading 0 looks down -z, forward = (sin h, 0, -cos h), right = (cos h, 0, sin h).
 */
class Road(seed: Long = 1L) {
    private val px = DoubleArray(N)
    private val pz = DoubleArray(N)
    private var last = 0
    private val ph = FloatArray(5)
    /** Bumped on every reset so renderers know cached geometry is stale. */
    var generation = 0
        private set

    init {
        reset(seed)
    }

    fun reset(seed: Long) {
        val r = Rng(seed * 7919L + 13L)
        for (i in ph.indices) ph[i] = r.range(0f, 6.2831855f)
        last = 0
        generation++
        px[0] = 0.0
        pz[0] = 0.0
    }

    private fun ramp(s: Double): Float {
        val t = ((s - 80.0) / 260.0).coerceIn(0.0, 1.0).toFloat()
        return t * t * (3f - 2f * t)
    }

    fun heading(s: Double): Float = ramp(s) * (
        0.50f * (sin(s / 230.0 + ph[0]) - sin(ph[0].toDouble())).toFloat() +
            0.22f * sin(s / 97.0 + ph[1]).toFloat() +
            0.07f * sin(s / 41.0 + ph[2]).toFloat()
        )

    /** d(heading)/ds; positive = bending right. */
    fun curvature(s: Double): Float = heading(s + 0.5) - heading(s - 0.5)

    fun height(s: Double): Float = ramp(s) * (
        2.6f * sin(s / 150.0 + ph[3]).toFloat() + 1.1f * sin(s / 63.0 + ph[4]).toFloat()
        )

    fun slope(s: Double): Float = height(s + 0.5) - height(s - 0.5)

    /** Makes sure centreline samples exist up to arc length [s]. O(1) amortised, no allocation. */
    fun ensure(s: Double) {
        val need = (s / STEP).toInt() + 2
        while (last < need) {
            val h = heading((last + 0.5) * STEP)
            val a = last and MASK
            val b = (last + 1) and MASK
            px[b] = px[a] + sin(h) * STEP
            pz[b] = pz[a] - cos(h) * STEP
            last++
        }
    }

    /** Oldest arc length still held in the ring. */
    val minS: Double get() = maxOf(0, last - N + 2) * STEP.toDouble()

    fun frame(s: Double, out: RoadFrame) {
        ensure(s)
        val ss = if (s < minS) minS else s
        val fi = ss / STEP
        val i = fi.toInt()
        val f = fi - i
        val a = i and MASK
        val b = (i + 1) and MASK
        out.x = px[a] + (px[b] - px[a]) * f
        out.z = pz[a] + (pz[b] - pz[a]) * f
        out.y = height(ss)
        out.heading = heading(ss)
        out.slope = slope(ss)
    }

    companion object {
        const val STEP = 2.0
        private const val N = 2048
        private const val MASK = N - 1
    }
}
