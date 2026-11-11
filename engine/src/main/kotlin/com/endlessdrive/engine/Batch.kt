package com.endlessdrive.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Dynamic batch of unlit coloured triangles: pos(3) rgba(4). Preallocated; flushes itself when full. */
class Batch(private val maxVerts: Int = 6000) {
    private val data = FloatArray(maxVerts * STRIDE)
    private val buffer: FloatBuffer =
        ByteBuffer.allocateDirect(maxVerts * STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var n = 0
    var vbo = 0
    lateinit var gl: Gl

    fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
        var o = n * STRIDE
        val d = data
        d[o++] = x; d[o++] = y; d[o++] = z; d[o++] = r; d[o++] = g; d[o++] = b; d[o] = a
        n++
    }

    /** Makes room for a primitive of [verts] vertices (flushing if needed). */
    fun reserve(verts: Int) {
        if (n + verts > maxVerts) flush()
    }

    fun quad(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        reserve(6)
        v(ax, ay, az, r, g, b, a); v(bx, by, bz, r, g, b, a); v(cx, cy, cz, r, g, b, a)
        v(ax, ay, az, r, g, b, a); v(cx, cy, cz, r, g, b, a); v(dx, dy, dz, r, g, b, a)
    }

    /**
     * Soft round glow: an 8-segment fan in the plane spanned by (ux,uy,uz) and (vx,vy,vz),
     * opaque-ish in the centre and fully transparent at the rim.
     */
    fun glow(
        x: Float, y: Float, z: Float, radU: Float, radV: Float,
        ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
        r: Float, g: Float, b: Float, a: Float, rimA: Float = 0f,
    ) {
        reserve(24)
        var px = x + ux * radU; var py = y + uy * radU; var pz = z + uz * radU
        for (i in 1..8) {
            val c = COS8[i and 7] * radU; val s = SIN8[i and 7] * radV
            val qx = x + ux * c + vx * s; val qy = y + uy * c + vy * s; val qz = z + uz * c + vz * s
            v(x, y, z, r, g, b, a); v(px, py, pz, r, g, b, rimA); v(qx, qy, qz, r, g, b, rimA)
            px = qx; py = qy; pz = qz
        }
    }

    fun flush() {
        if (n == 0) return
        buffer.clear()
        buffer.put(data, 0, n * STRIDE)
        buffer.position(0)
        gl.bindArrayBuffer(vbo)
        gl.bufferData(buffer, n * STRIDE, true)
        gl.vertexAttribPointer(0, 3, STRIDE * 4, 0)
        gl.vertexAttribPointer(1, 4, STRIDE * 4, 12)
        gl.drawTriangles(0, n)
        n = 0
    }

    companion object {
        const val STRIDE = 7
        private val COS8 = FloatArray(8) { kotlin.math.cos(it * 0.7853982f) }
        private val SIN8 = FloatArray(8) { kotlin.math.sin(it * 0.7853982f) }
    }
}
