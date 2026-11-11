package com.endlessdrive.core.fx

/** Fixed pool of particles in road space (s along, x across, y up). No allocation after construction. */
class ParticleSystem(val capacity: Int) {
    val s = DoubleArray(capacity)
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vs = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val life = FloatArray(capacity)
    val maxLife = FloatArray(capacity)
    val size = FloatArray(capacity)
    val r = FloatArray(capacity)
    val g = FloatArray(capacity)
    val b = FloatArray(capacity)
    val gravity = FloatArray(capacity)
    val additive = BooleanArray(capacity)
    /** Particles beyond this index are never used (quality presets lower it). */
    var limit = capacity
    private var cursor = 0

    fun clear() {
        for (i in 0 until capacity) life[i] = 0f
    }

    fun emit(
        s0: Double, x0: Float, y0: Float, vs0: Float, vx0: Float, vy0: Float,
        lifeS: Float, sz: Float, cr: Float, cg: Float, cb: Float, grav: Float, add: Boolean,
    ) {
        var i = cursor
        var tries = 0
        while (life[i] > 0f && tries < 8) { // prefer a free slot nearby, else overwrite
            i++; if (i >= limit) i = 0
            tries++
        }
        cursor = i + 1
        if (cursor >= limit) cursor = 0
        if (i >= limit) i = 0
        s[i] = s0; x[i] = x0; y[i] = y0; vs[i] = vs0; vx[i] = vx0; vy[i] = vy0
        life[i] = lifeS; maxLife[i] = lifeS; size[i] = sz
        r[i] = cr; g[i] = cg; b[i] = cb; gravity[i] = grav; additive[i] = add
    }

    fun update(dt: Float) {
        for (i in 0 until limit) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            vy[i] -= gravity[i] * dt
            s[i] += vs[i] * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            if (y[i] < 0.03f) {
                y[i] = 0.03f
                vy[i] = -vy[i] * 0.35f
                vs[i] *= 0.7f; vx[i] *= 0.7f
            }
        }
    }

    fun activeCount(): Int {
        var n = 0
        for (i in 0 until limit) if (life[i] > 0f) n++
        return n
    }
}
