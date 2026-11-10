package com.endlessdrive.core.fx

import com.endlessdrive.core.Rng
import kotlin.math.cos
import kotlin.math.sin

/** Fixed-capacity struct-of-arrays particle pool in world units. Zero allocation after init. */
class ParticleSystem(val capacity: Int = 384, seed: Long = 7L) {
    val x = FloatArray(capacity)
    val z = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vz = FloatArray(capacity)
    val life = FloatArray(capacity)
    val maxLife = FloatArray(capacity)
    val size = FloatArray(capacity)
    val grow = FloatArray(capacity)
    val color = IntArray(capacity)
    private val rng = Rng(seed)
    private var cursor = 0

    var alive = 0
        private set

    fun clear() {
        for (i in 0 until capacity) life[i] = 0f
        alive = 0
    }

    fun emit(
        px: Float, pz: Float, count: Int, speedMin: Float, speedMax: Float,
        lifeMin: Float, lifeMax: Float, sizeStart: Float, sizeGrow: Float, argb: Int,
        baseVx: Float = 0f, baseVz: Float = 0f,
    ) {
        for (n in 0 until count) {
            val i = cursor
            cursor = (cursor + 1) % capacity
            val a = rng.nextFloat() * 6.2831855f
            val s = rng.range(speedMin, speedMax)
            x[i] = px
            z[i] = pz
            vx[i] = cos(a) * s + baseVx
            vz[i] = sin(a) * s + baseVz
            val l = rng.range(lifeMin, lifeMax)
            life[i] = l
            maxLife[i] = l
            size[i] = sizeStart * rng.range(0.6f, 1.2f)
            grow[i] = sizeGrow
            color[i] = argb
        }
    }

    fun update(dt: Float) {
        var count = 0
        val drag = 1f - (2.5f * dt).coerceAtMost(0.9f)
        for (i in 0 until capacity) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) continue
            x[i] += vx[i] * dt
            z[i] += vz[i] * dt
            vx[i] *= drag
            vz[i] *= drag
            size[i] += grow[i] * dt
            count++
        }
        alive = count
    }

    /** 1 at birth -> 0 at death. */
    fun alpha(i: Int): Float = if (maxLife[i] <= 0f) 0f else (life[i] / maxLife[i]).coerceIn(0f, 1f)
}
