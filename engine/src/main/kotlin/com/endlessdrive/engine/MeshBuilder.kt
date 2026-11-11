package com.endlessdrive.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds flat-shaded, vertex-coloured triangle soup. Vertex = pos(3) normal(3) colour(3) material(1).
 * Face normals are computed per face and flipped towards a hint, and back-face culling is off,
 * so winding never matters. The backing array only grows (no steady-state allocation).
 *
 * Materials: 0 lit, 1 emissive (unlit), 2 body paint (lit, multiplied by the per-object tint),
 * 3 night light (dark glass by day, glows with its colour at night).
 */
class MeshBuilder(initialVerts: Int = 4096) {
    var data = FloatArray(initialVerts * STRIDE)
        private set
    var vertexCount = 0
        private set

    private var cr = 1f; private var cg = 1f; private var cb = 1f
    private var mat = 0f
    // placement transform: scale, yaw, then translate
    private var tx = 0f; private var ty = 0f; private var tz = 0f
    private var tc = 1f; private var ts = 0f; private var tscale = 1f; private var tscaleY = 1f

    fun reset() {
        vertexCount = 0
        identity()
        mat = 0f
    }

    fun identity() {
        tx = 0f; ty = 0f; tz = 0f; tc = 1f; ts = 0f; tscale = 1f; tscaleY = 1f
    }

    fun place(x: Float, y: Float, z: Float, yaw: Float = 0f, scale: Float = 1f, scaleY: Float = scale) {
        tx = x; ty = y; tz = z; tc = cos(yaw); ts = sin(yaw); tscale = scale; tscaleY = scaleY
    }

    fun color(rgb: Int, material: Int = 0) {
        cr = ((rgb shr 16) and 255) / 255f
        cg = ((rgb shr 8) and 255) / 255f
        cb = (rgb and 255) / 255f
        mat = material.toFloat()
    }

    fun colorF(r: Float, g: Float, b: Float, material: Int = 0) {
        cr = r; cg = g; cb = b; mat = material.toFloat()
    }

    private fun ensure(extraVerts: Int) {
        val need = (vertexCount + extraVerts) * STRIDE
        if (need > data.size) data = data.copyOf(maxOf(need, data.size * 2))
    }

    private fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        var o = vertexCount * STRIDE
        val d = data
        d[o++] = tx + (x * tc + z * ts) * tscale
        d[o++] = ty + y * tscaleY
        d[o++] = tz + (-x * ts + z * tc) * tscale
        d[o++] = nx * tc + nz * ts
        d[o++] = ny
        d[o++] = -nx * ts + nz * tc
        d[o++] = cr; d[o++] = cg; d[o++] = cb
        d[o] = mat
        vertexCount++
    }

    /** Triangle with a flat normal flipped to agree with hint (hx,hy,hz); a zero hint keeps the winding normal. */
    fun tri(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float,
    ) {
        ensure(3)
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 1e-12f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 1f; nz = 0f }
        if (nx * hx + ny * hy + nz * hz < 0f) { nx = -nx; ny = -ny; nz = -nz }
        put(ax, ay, az, nx, ny, nz); put(bx, by, bz, nx, ny, nz); put(cx, cy, cz, nx, ny, nz)
    }

    /** Quad a-b-c-d (corners in loop order). */
    fun quad(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
        hx: Float, hy: Float, hz: Float,
    ) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, hx, hy, hz)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, hx, hy, hz)
    }

    /** Axis-aligned box given centre and half extents. */
    fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float) {
        frustum(hx, cy - hy, cz - hz, cz + hz, hx, cy + hy, cz - hz, cz + hz, cx)
    }

    /**
     * Rectangular frustum centred on x = cx: bottom rectangle (half width w0, z from z0a to z0b) at
     * height y0, top rectangle (w1, z1a..z1b) at y1. Covers boxes, wedges, cabins, tapered cones.
     */
    fun frustum(w0: Float, y0: Float, z0a: Float, z0b: Float, w1: Float, y1: Float, z1a: Float, z1b: Float, cx: Float = 0f) {
        val l0 = cx - w0; val r0 = cx + w0; val l1 = cx - w1; val r1 = cx + w1
        // bottom, top
        quad(l0, y0, z0a, r0, y0, z0a, r0, y0, z0b, l0, y0, z0b, 0f, -1f, 0f)
        quad(l1, y1, z1a, r1, y1, z1a, r1, y1, z1b, l1, y1, z1b, 0f, 1f, 0f)
        // front (-z), back (+z)
        quad(l0, y0, z0a, r0, y0, z0a, r1, y1, z1a, l1, y1, z1a, 0f, 0f, -1f)
        quad(l0, y0, z0b, r0, y0, z0b, r1, y1, z1b, l1, y1, z1b, 0f, 0f, 1f)
        // left, right
        quad(l0, y0, z0a, l0, y0, z0b, l1, y1, z1b, l1, y1, z1a, -1f, 0f, 0f)
        quad(r0, y0, z0a, r0, y0, z0b, r1, y1, z1b, r1, y1, z1a, 1f, 0f, 0f)
    }

    /** N-sided cone / truncated cone around a vertical axis. */
    fun cone(cx: Float, y0: Float, cz: Float, r0: Float, y1: Float, r1: Float, sides: Int, phase: Float = 0f) {
        val step = 6.2831855f / sides
        for (i in 0 until sides) {
            val a0 = phase + i * step; val a1 = a0 + step
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val hx = cos(a0 + step * 0.5f); val hz = sin(a0 + step * 0.5f)
            if (r1 <= 0.001f) {
                tri(cx + c0 * r0, y0, cz + s0 * r0, cx + c1 * r0, y0, cz + s1 * r0, cx, y1, cz, hx, 0.3f, hz)
            } else {
                quad(
                    cx + c0 * r0, y0, cz + s0 * r0, cx + c1 * r0, y0, cz + s1 * r0,
                    cx + c1 * r1, y1, cz + s1 * r1, cx + c0 * r1, y1, cz + s0 * r1, hx, 0.2f, hz,
                )
            }
        }
    }

    /** N-gon disc in the x-y plane (facing +-z) with thickness; used for coins and round signs. */
    fun disc(cx: Float, cy: Float, cz: Float, r: Float, halfT: Float, sides: Int) {
        val step = 6.2831855f / sides
        for (i in 0 until sides) {
            val a0 = i * step + step * 0.5f; val a1 = a0 + step
            val x0 = cx + cos(a0) * r; val y0 = cy + sin(a0) * r
            val x1 = cx + cos(a1) * r; val y1 = cy + sin(a1) * r
            tri(cx, cy, cz - halfT, x0, y0, cz - halfT, x1, y1, cz - halfT, 0f, 0f, -1f)
            tri(cx, cy, cz + halfT, x0, y0, cz + halfT, x1, y1, cz + halfT, 0f, 0f, 1f)
            if (halfT > 0.011f) {
                quad(x0, y0, cz - halfT, x1, y1, cz - halfT, x1, y1, cz + halfT, x0, y0, cz + halfT, cos(a0 + step * 0.5f), sin(a0 + step * 0.5f), 0f)
            }
        }
    }

    companion object {
        const val STRIDE = 10
    }
}
