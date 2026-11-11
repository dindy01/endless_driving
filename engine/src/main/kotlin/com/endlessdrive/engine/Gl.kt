package com.endlessdrive.engine

import java.nio.FloatBuffer

/**
 * The tiny slice of OpenGL ES 2.0 the renderer needs. Implemented by AndroidGl (GLES20) in the app
 * and by LwjglGl in the desktop preview harness, so the exact same rendering code runs on both.
 */
interface Gl {
    /** Prepended to vertex / fragment shader sources (precision qualifiers, #version...). */
    val vertexPrefix: String
    val fragmentPrefix: String

    fun viewport(x: Int, y: Int, w: Int, h: Int)
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(mask: Int)
    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFunc(src: Int, dst: Int)
    fun depthMask(on: Boolean)

    /** Compiles and links; binds [attribs] to locations 0..n-1. Throws IllegalStateException with the log on failure. */
    fun createProgram(vertexSrc: String, fragmentSrc: String, attribs: Array<String>): Int
    fun useProgram(program: Int)
    fun uniformLocation(program: Int, name: String): Int
    fun uniform1f(loc: Int, x: Float)
    fun uniform3f(loc: Int, x: Float, y: Float, z: Float)
    fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float)
    fun uniformMatrix4(loc: Int, m: FloatArray)

    fun createBuffer(): Int
    fun bindArrayBuffer(id: Int)
    /** Uploads the first [floatCount] floats of [data] (position 0) to the bound buffer. */
    fun bufferData(data: FloatBuffer, floatCount: Int, dynamic: Boolean)
    fun enableVertexAttrib(index: Int)
    fun disableVertexAttrib(index: Int)
    fun vertexAttribPointer(index: Int, size: Int, strideBytes: Int, offsetBytes: Int)
    fun drawTriangles(first: Int, count: Int)

    companion object {
        const val DEPTH_TEST = 0x0B71
        const val CULL_FACE = 0x0B44
        const val BLEND = 0x0BE2
        const val COLOR_BUFFER_BIT = 0x4000
        const val DEPTH_BUFFER_BIT = 0x0100
        const val SRC_ALPHA = 0x0302
        const val ONE_MINUS_SRC_ALPHA = 0x0303
        const val ONE = 1
    }
}

/** Graphics quality preset: scales draw distance, shadows, particles and scenery density. */
class Quality(
    val name: String,
    val drawDistance: Float,
    /** 0 = none, 1 = blob shadows, 2 = blob + sun-offset soft shadows. */
    val shadows: Int,
    val particles: Int,
    val scenery: Float,
    val rainDrops: Int,
    val speedLines: Int,
    /** Fraction of native resolution to render at (applied by the platform layer). */
    val resolutionScale: Float,
) {
    companion object {
        val LOW = Quality("low", 230f, 0, 96, 0.45f, 60, 14, 0.7f)
        val MEDIUM = Quality("medium", 320f, 1, 176, 0.75f, 110, 22, 0.85f)
        val HIGH = Quality("high", 410f, 2, 256, 1f, 170, 30, 1f)
        fun of(index: Int): Quality = when (index) { 0 -> LOW; 2 -> HIGH; else -> MEDIUM }
    }
}
