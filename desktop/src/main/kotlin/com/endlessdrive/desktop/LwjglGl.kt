package com.endlessdrive.desktop

import com.endlessdrive.engine.Gl
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import java.nio.FloatBuffer

/** Desktop OpenGL 2.1 backend for the engine's Gl interface (GLSL 1.20 accepts the ES 1.00 shaders with a prefix). */
class LwjglGl(es: Boolean) : Gl {
    // es = true compiles the shaders exactly as Android does (GLSL ES 1.00 rules, via Mesa's
    // ES2-compatibility), which catches precision / implicit-conversion errors a desktop compiler accepts.
    override val vertexPrefix = if (es) "#version 100\n" else "#version 120\n"
    override val fragmentPrefix = if (es) "#version 100\nprecision mediump float;\n" else "#version 120\n"

    override fun viewport(x: Int, y: Int, w: Int, h: Int) = GL11.glViewport(x, y, w, h)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GL11.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GL11.glClear(mask)
    override fun enable(cap: Int) = GL11.glEnable(cap)
    override fun disable(cap: Int) = GL11.glDisable(cap)
    override fun blendFunc(src: Int, dst: Int) = GL11.glBlendFunc(src, dst)
    override fun depthMask(on: Boolean) = GL11.glDepthMask(on)

    private fun compile(type: Int, src: String): Int {
        val s = GL20.glCreateShader(type)
        GL20.glShaderSource(s, src)
        GL20.glCompileShader(s)
        check(GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) != 0) { "shader compile failed: " + GL20.glGetShaderInfoLog(s) }
        return s
    }

    override fun createProgram(vertexSrc: String, fragmentSrc: String, attribs: Array<String>): Int {
        val p = GL20.glCreateProgram()
        GL20.glAttachShader(p, compile(GL20.GL_VERTEX_SHADER, vertexSrc))
        GL20.glAttachShader(p, compile(GL20.GL_FRAGMENT_SHADER, fragmentSrc))
        for (i in attribs.indices) GL20.glBindAttribLocation(p, i, attribs[i])
        GL20.glLinkProgram(p)
        check(GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) != 0) { "link failed: " + GL20.glGetProgramInfoLog(p) }
        return p
    }

    override fun useProgram(program: Int) = GL20.glUseProgram(program)
    override fun uniformLocation(program: Int, name: String) = GL20.glGetUniformLocation(program, name)
    override fun uniform1f(loc: Int, x: Float) = GL20.glUniform1f(loc, x)
    override fun uniform3f(loc: Int, x: Float, y: Float, z: Float) = GL20.glUniform3f(loc, x, y, z)
    override fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float) = GL20.glUniform4f(loc, x, y, z, w)
    override fun uniformMatrix4(loc: Int, m: FloatArray) = GL20.glUniformMatrix4fv(loc, false, m)
    override fun createBuffer() = GL15.glGenBuffers()
    override fun bindArrayBuffer(id: Int) = GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id)

    override fun bufferData(data: FloatBuffer, floatCount: Int, dynamic: Boolean) {
        data.limit(floatCount)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, if (dynamic) GL15.GL_DYNAMIC_DRAW else GL15.GL_STATIC_DRAW)
    }

    override fun enableVertexAttrib(index: Int) = GL20.glEnableVertexAttribArray(index)
    override fun disableVertexAttrib(index: Int) = GL20.glDisableVertexAttribArray(index)
    override fun vertexAttribPointer(index: Int, size: Int, strideBytes: Int, offsetBytes: Int) =
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, strideBytes, offsetBytes.toLong())

    override fun drawTriangles(first: Int, count: Int) = GL11.glDrawArrays(GL11.GL_TRIANGLES, first, count)
}
