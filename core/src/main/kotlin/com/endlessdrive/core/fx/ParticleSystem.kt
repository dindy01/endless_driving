package com.endlessdrive.core.fx

import com.endlessdrive.core.Rng
import kotlin.math.cos
import kotlin.math.sin

/** Fixed-capacity struct-of-arrays particles in the road frame (s, x, y). Zero allocation. */
class ParticleSystem(val capacity: Int = 400, seed: Long = 7L) {
    val s = DoubleArray(capacity)
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vs = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val life = FloatArray(capacity)
    val maxLife = FloatArray(capacity)
    val size = FloatArray(capacity)
    val grow = FloatArray(capacity)
    val color = IntArray(capacity)
    val gravity = FloatArray(capacity)
    private val rng = Rng(seed)
    private var cursor = 0
    /** Quality preset can lower the effective budget. */
    var limit = capacity
    var alive = 0
        private set

    fun clear() {
        for (i in 0 until capacity) life[i] = 0f
        alive = 0
    }

    fun emit(
        ps: Double, px: Float, py: Float, count: Int, speedMin: Float, speedMax: Float,
        lifeMin: Float, lifeMax: Float, size0: Float, sizeGrow: Float, argb: Int,
        baseVs: Float = 0f, upBias: Float = 0.5f, g: Float = 9f,
    ) {
        val n = if (count > limit) limit else count
        for (k in 0 until n) {
            val i = cursor
            cursor = (cursor + 1) % limit.coerceIn(1, capacity)
            val a = rng.nextFloat() * 6.2831855f
            val b = rng.nextFloat() * 3.1415927f * upBias
            val sp = rng.range(speedMin, speedMax)
            s[i] = ps
            x[i] = px
            y[i] = py
            vs[i] = cos(a) * cos(b) * sp + baseVs
            vx[i] = sin(a) * cos(b) * sp
            vy[i] = sin(b) * sp
            val l = rng.range(lifeMin, lifeMax)
            life[i] = l
            maxLife[i] = l
            size[i] = size0 * rng.range(0.6f, 1.3f)
            grow[i] = sizeGrow
            color[i] = argb
            gravity[i] = g
        }
    }

    fun update(dt: Float) {
        var n = 0
        val drag = 1f - (1.8f * dt).coerceAtMost(0.9f)
        for (i in 0 until capacity) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) continue
            s[i] += vs[i] * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            vy[i] -= gravity[i] * dt
            if (y[i] < 0.05f) {
                y[i] = 0.05f
                vy[i] = -vy[i] * 0.3f
            }
            vs[i] *= drag
            vx[i] *= drag
            size[i] += grow[i] * dt
            n++
        }
        alive = n
    }

    fun alpha(i: Int): Float = if (maxLife[i] <= 0f) 0f else (life[i] / maxLife[i]).coerceIn(0f, 1f)
}
