package com.endlessdrive.render

import org.lwjgl.opengles.GLES30
import java.nio.FloatBuffer

/** Desktop GLES 3.0 implementation of [GL] (LWJGL) used for headless render tests. */
class LwjglGL : GL {
    override fun viewport(x: Int, y: Int, w: Int, h: Int) = GLES30.glViewport(x, y, w, h)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES30.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GLES30.glClear(mask)
    override fun enable(cap: Int) = GLES30.glEnable(cap)
    override fun disable(cap: Int) = GLES30.glDisable(cap)
    override fun blendFunc(src: Int, dst: Int) = GLES30.glBlendFunc(src, dst)
    override fun depthMask(flag: Boolean) = GLES30.glDepthMask(flag)
    override fun lineWidth(w: Float) = GLES30.glLineWidth(w)
    override fun createShader(type: Int) = GLES30.glCreateShader(type)
    override fun shaderSource(shader: Int, src: String) = GLES30.glShaderSource(shader, src)
    override fun compileShader(shader: Int) = GLES30.glCompileShader(shader)
    override fun getShaderCompileStatus(shader: Int) = GLES30.glGetShaderi(shader, GLES30.GL_COMPILE_STATUS) != 0
    override fun getShaderInfoLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader)
    override fun createProgram() = GLES30.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES30.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = GLES30.glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = GLES30.glLinkProgram(program)
    override fun getProgramLinkStatus(program: Int) = GLES30.glGetProgrami(program, GLES30.GL_LINK_STATUS) != 0
    override fun getProgramInfoLog(program: Int): String = GLES30.glGetProgramInfoLog(program)
    override fun useProgram(program: Int) = GLES30.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)
    override fun genBuffer() = GLES30.glGenBuffers()
    override fun bindBuffer(target: Int, buffer: Int) = GLES30.glBindBuffer(target, buffer)
    override fun bufferData(target: Int, sizeBytes: Int, data: FloatBuffer?, usage: Int) {
        if (data == null) GLES30.glBufferData(target, sizeBytes.toLong(), usage) else GLES30.glBufferData(target, data, usage)
    }
    override fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: FloatBuffer) =
        GLES30.glBufferSubData(target, offsetBytes.toLong(), data)
    override fun genVertexArray() = GLES30.glGenVertexArrays()
    override fun bindVertexArray(vao: Int) = GLES30.glBindVertexArray(vao)
    override fun enableVertexAttribArray(index: Int) = GLES30.glEnableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, stride: Int, offsetBytes: Int) =
        GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, stride, offsetBytes.toLong())
    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES30.glDrawArrays(mode, first, count)
    override fun uniformMatrix4fv(location: Int, value: FloatArray) = GLES30.glUniformMatrix4fv(location, false, value)
    override fun uniform1f(location: Int, x: Float) = GLES30.glUniform1f(location, x)
    override fun uniform1i(location: Int, x: Int) = GLES30.glUniform1i(location, x)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = GLES30.glUniform3f(location, x, y, z)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = GLES30.glUniform4f(location, x, y, z, w)
}
