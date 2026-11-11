package com.endlessdrive.game

import android.opengl.GLES20
import com.endlessdrive.engine.Gl
import java.nio.FloatBuffer

/** OpenGL ES 2.0 backend for the engine. */
class AndroidGl : Gl {
    override val vertexPrefix = ""
    override val fragmentPrefix = "precision mediump float;\n"
    private val tmp = IntArray(1)

    override fun viewport(x: Int, y: Int, w: Int, h: Int) = GLES20.glViewport(x, y, w, h)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES20.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GLES20.glClear(mask)
    override fun enable(cap: Int) = GLES20.glEnable(cap)
    override fun disable(cap: Int) = GLES20.glDisable(cap)
    override fun blendFunc(src: Int, dst: Int) = GLES20.glBlendFunc(src, dst)
    override fun depthMask(on: Boolean) = GLES20.glDepthMask(on)

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, tmp, 0)
        check(tmp[0] != 0) { "shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    override fun createProgram(vertexSrc: String, fragmentSrc: String, attribs: Array<String>): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertexSrc))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc))
        for (i in attribs.indices) GLES20.glBindAttribLocation(p, i, attribs[i])
        GLES20.glLinkProgram(p)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, tmp, 0)
        check(tmp[0] != 0) { "program link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    override fun useProgram(program: Int) = GLES20.glUseProgram(program)
    override fun uniformLocation(program: Int, name: String) = GLES20.glGetUniformLocation(program, name)
    override fun uniform1f(loc: Int, x: Float) = GLES20.glUniform1f(loc, x)
    override fun uniform3f(loc: Int, x: Float, y: Float, z: Float) = GLES20.glUniform3f(loc, x, y, z)
    override fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float) = GLES20.glUniform4f(loc, x, y, z, w)
    override fun uniformMatrix4(loc: Int, m: FloatArray) = GLES20.glUniformMatrix4fv(loc, 1, false, m, 0)

    override fun createBuffer(): Int {
        GLES20.glGenBuffers(1, tmp, 0)
        return tmp[0]
    }

    override fun bindArrayBuffer(id: Int) = GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)

    override fun bufferData(data: FloatBuffer, floatCount: Int, dynamic: Boolean) =
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floatCount * 4, data, if (dynamic) GLES20.GL_DYNAMIC_DRAW else GLES20.GL_STATIC_DRAW)

    override fun enableVertexAttrib(index: Int) = GLES20.glEnableVertexAttribArray(index)
    override fun disableVertexAttrib(index: Int) = GLES20.glDisableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, strideBytes: Int, offsetBytes: Int) =
        GLES20.glVertexAttribPointer(index, size, GLES20.GL_FLOAT, false, strideBytes, offsetBytes)

    override fun drawTriangles(first: Int, count: Int) = GLES20.glDrawArrays(GLES20.GL_TRIANGLES, first, count)
}
