package com.endlessdrive.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Per-frame dynamic geometry for unlit effects (particles, glows, rain, shadows, speed lines).
 * Format: pos xyz, rgba, size (8 floats). One VBO; segments drawn with different primitives.
 */
class FxBatch(private val capacityVerts: Int = 12000) {
    val data = FloatArray(capacityVerts * FLOATS)
    var count = 0; private set
    private val buf: FloatBuffer =
        ByteBuffer.allocateDirect(capacityVerts * FLOATS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var vao = 0
    private var vbo = 0

    fun init(gl: GL) {
        vao = gl.genVertexArray()
        gl.bindVertexArray(vao)
        vbo = gl.genBuffer()
        gl.bindBuffer(GLC.ARRAY_BUFFER, vbo)
        gl.bufferData(GLC.ARRAY_BUFFER, capacityVerts * FLOATS * 4, null, GLC.DYNAMIC_DRAW)
        gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, FLOATS * 4, 0)
        gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 4, FLOATS * 4, 12)
        gl.enableVertexAttribArray(2); gl.vertexAttribPointer(2, 1, FLOATS * 4, 28)
        gl.bindVertexArray(0)
    }

    fun begin() { count = 0 }

    fun full(n: Int = 1) = count + n > capacityVerts

    fun v(x: Float, y: Float, z: Float, argb: Int, alphaMul: Float = 1f, size: Float = 1f) {
        if (count >= capacityVerts) return
        var i = count * FLOATS
        val d = data
        d[i++] = x; d[i++] = y; d[i++] = z
        d[i++] = ((argb shr 16) and 255) / 255f
        d[i++] = ((argb shr 8) and 255) / 255f
        d[i++] = (argb and 255) / 255f
        d[i++] = ((argb ushr 24) and 255) / 255f * alphaMul
        d[i] = size
        count++
    }

    /** Uploads everything written since begin(). */
    fun upload(gl: GL) {
        if (count == 0) return
        buf.clear()
        buf.put(data, 0, count * FLOATS)
        buf.flip()
        gl.bindBuffer(GLC.ARRAY_BUFFER, vbo)
        gl.bufferSubData(GLC.ARRAY_BUFFER, 0, count * FLOATS * 4, buf)
    }

    fun draw(gl: GL, mode: Int, first: Int, n: Int) {
        if (n <= 0) return
        gl.bindVertexArray(vao)
        gl.drawArrays(mode, first, n)
    }

    companion object {
        const val FLOATS = 8
    }
}
