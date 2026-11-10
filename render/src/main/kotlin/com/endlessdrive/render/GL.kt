package com.endlessdrive.render

import java.nio.FloatBuffer

/**
 * The subset of OpenGL ES 3.0 the renderer uses. Implemented by AndroidGL (android.opengl.GLES30)
 * and by a desktop LWJGL implementation in tests. All methods are allocation-free.
 */
interface GL {
    fun viewport(x: Int, y: Int, w: Int, h: Int)
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(mask: Int)
    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFunc(src: Int, dst: Int)
    fun depthMask(flag: Boolean)
    fun lineWidth(w: Float)

    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, src: String)
    fun compileShader(shader: Int)
    fun getShaderCompileStatus(shader: Int): Boolean
    fun getShaderInfoLog(shader: Int): String
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun bindAttribLocation(program: Int, index: Int, name: String)
    fun linkProgram(program: Int)
    fun getProgramLinkStatus(program: Int): Boolean
    fun getProgramInfoLog(program: Int): String
    fun useProgram(program: Int)
    fun getUniformLocation(program: Int, name: String): Int

    fun genBuffer(): Int
    fun bindBuffer(target: Int, buffer: Int)
    fun bufferData(target: Int, sizeBytes: Int, data: FloatBuffer?, usage: Int)
    fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: FloatBuffer)
    fun genVertexArray(): Int
    fun bindVertexArray(vao: Int)
    fun enableVertexAttribArray(index: Int)
    fun vertexAttribPointer(index: Int, size: Int, stride: Int, offsetBytes: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)

    fun uniformMatrix4fv(location: Int, value: FloatArray)
    fun uniform1f(location: Int, x: Float)
    fun uniform1i(location: Int, x: Int)
    fun uniform3f(location: Int, x: Float, y: Float, z: Float)
    fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)
}

/** Standard GL enum values (identical on GLES and desktop). */
object GLC {
    const val COLOR_BUFFER_BIT = 0x4000
    const val DEPTH_BUFFER_BIT = 0x0100
    const val DEPTH_TEST = 0x0B71
    const val BLEND = 0x0BE2
    const val CULL_FACE = 0x0B44
    const val SRC_ALPHA = 0x0302
    const val ONE_MINUS_SRC_ALPHA = 0x0303
    const val ONE = 1
    const val VERTEX_SHADER = 0x8B31
    const val FRAGMENT_SHADER = 0x8B30
    const val ARRAY_BUFFER = 0x8892
    const val STATIC_DRAW = 0x88E4
    const val DYNAMIC_DRAW = 0x88E8
    const val TRIANGLES = 0x0004
    const val LINES = 0x0001
    const val POINTS = 0x0000
}
