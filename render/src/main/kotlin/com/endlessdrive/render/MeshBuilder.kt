package com.endlessdrive.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds flat-shaded low-poly geometry. Vertex format (10 floats): position xyz, normal xyz,
 * colour rgb, emissive (0..1, lights/windows that glow at night). Used at load time and for
 * streamed road chunks (reset() reuses the same backing array: no allocation once grown).
 */
class MeshBuilder(initialFloats: Int = 4096) {
    var data = FloatArray(initialFloats); private set
    var size = 0; private set
    val vertexCount: Int get() = size / FLOATS

    private var cr = 1f; private var cg = 1f; private var cb = 1f; private var ce = 0f

    fun reset() { size = 0 }

    fun color(argbOrRgb: Int, emissive: Float = 0f): MeshBuilder {
        cr = ((argbOrRgb shr 16) and 255) / 255f
        cg = ((argbOrRgb shr 8) and 255) / 255f
        cb = (argbOrRgb and 255) / 255f
        ce = emissive
        return this
    }

    fun colorF(r: Float, g: Float, b: Float, emissive: Float = 0f): MeshBuilder {
        cr = r; cg = g; cb = b; ce = emissive
        return this
    }

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        ensure(FLOATS)
        val d = data
        var i = size
        d[i++] = x; d[i++] = y; d[i++] = z
        d[i++] = nx; d[i++] = ny; d[i++] = nz
        d[i++] = cr; d[i++] = cg; d[i++] = cb; d[i++] = ce
        size = i
    }

    /** Triangle with a flat normal computed from the winding; flipped to face away from (ox,oy,oz). */
    fun tri(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float,
        ox: Float = Float.NaN, oy: Float = 0f, oz: Float = 0f,
    ) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-9f) return
        nx /= l; ny /= l; nz /= l
        if (!ox.isNaN()) {
            val mx = (ax + bx + cx) / 3f - ox; val my = (ay + by + cy) / 3f - oy; val mz = (az + bz + cz) / 3f - oz
            if (nx * mx + ny * my + nz * mz < 0f) { nx = -nx; ny = -ny; nz = -nz }
        } else if (ny < 0f && kotlin.math.abs(ny) > 0.5f) {
            nx = -nx; ny = -ny; nz = -nz
        }
        vertex(ax, ay, az, nx, ny, nz); vertex(bx, by, bz, nx, ny, nz); vertex(cx, cy, cz, nx, ny, nz)
    }

    fun quad(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
        ox: Float = Float.NaN, oy: Float = 0f, oz: Float = 0f,
    ) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, ox, oy, oz)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, ox, oy, oz)
    }

    /**
     * General hexahedron from 8 corners: bottom (0..3 around) and top (4..7) in the same order.
     * Normals point away from the centroid.
     */
    fun hexa(c: FloatArray) {
        var ox = 0f; var oy = 0f; var oz = 0f
        for (i in 0 until 8) { ox += c[i * 3]; oy += c[i * 3 + 1]; oz += c[i * 3 + 2] }
        ox /= 8; oy /= 8; oz /= 8
        face(c, 0, 1, 2, 3, ox, oy, oz); face(c, 4, 5, 6, 7, ox, oy, oz)
        face(c, 0, 1, 5, 4, ox, oy, oz); face(c, 1, 2, 6, 5, ox, oy, oz)
        face(c, 2, 3, 7, 6, ox, oy, oz); face(c, 3, 0, 4, 7, ox, oy, oz)
    }

    private fun face(c: FloatArray, a: Int, b: Int, cc: Int, d: Int, ox: Float, oy: Float, oz: Float) = quad(
        c[a * 3], c[a * 3 + 1], c[a * 3 + 2], c[b * 3], c[b * 3 + 1], c[b * 3 + 2],
        c[cc * 3], c[cc * 3 + 1], c[cc * 3 + 2], c[d * 3], c[d * 3 + 1], c[d * 3 + 2], ox, oy, oz,
    )

    private val hx = FloatArray(24)

    private fun corner(i: Int, px: Float, py: Float, pz: Float) {
        hx[i * 3] = px; hx[i * 3 + 1] = py; hx[i * 3 + 2] = pz
    }

    /** Axis-aligned box centred at (x,y,z) with half extents. */
    fun box(x: Float, y: Float, z: Float, ex: Float, ey: Float, ez: Float): MeshBuilder =
        taper(x, y, z, ex, ey, ez, ex, ez, 0f)

    /** Box whose top face is (topEx, topEz) and shifted by topDz along z (cabins, tapered noses). */
    fun taper(x: Float, y: Float, z: Float, ex: Float, ey: Float, ez: Float, topEx: Float, topEz: Float, topDz: Float): MeshBuilder {
        corner(0, x - ex, y - ey, z - ez); corner(1, x + ex, y - ey, z - ez)
        corner(2, x + ex, y - ey, z + ez); corner(3, x - ex, y - ey, z + ez)
        corner(4, x - topEx, y + ey, z - topEz + topDz); corner(5, x + topEx, y + ey, z - topEz + topDz)
        corner(6, x + topEx, y + ey, z + topEz + topDz); corner(7, x - topEx, y + ey, z + topEz + topDz)
        hexa(hx)
        return this
    }

    /**
     * Appends all vertices of [src] transformed by the 3x4 part of column-major [m]
     * (normals by its rotation part). Used to bake scenery into road chunks.
     */
    fun append(src: MeshBuilder, m: FloatArray) {
        ensure(src.size)
        val sd = src.data
        val d = data
        var o = size
        var i = 0
        while (i < src.size) {
            val x = sd[i]; val y = sd[i + 1]; val z = sd[i + 2]
            val nx = sd[i + 3]; val ny = sd[i + 4]; val nz = sd[i + 5]
            d[o] = m[0] * x + m[4] * y + m[8] * z + m[12]
            d[o + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
            d[o + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
            var tx = m[0] * nx + m[4] * ny + m[8] * nz
            var ty = m[1] * nx + m[5] * ny + m[9] * nz
            var tz = m[2] * nx + m[6] * ny + m[10] * nz
            val l = sqrt(tx * tx + ty * ty + tz * tz)
            if (l > 0f) { tx /= l; ty /= l; tz /= l }
            d[o + 3] = tx; d[o + 4] = ty; d[o + 5] = tz
            d[o + 6] = sd[i + 6]; d[o + 7] = sd[i + 7]; d[o + 8] = sd[i + 8]; d[o + 9] = sd[i + 9]
            o += FLOATS
            i += FLOATS
        }
        size = o
    }

    /** Cylinder along the X axis (wheels). */
    fun cylinderX(x: Float, y: Float, z: Float, r: Float, halfW: Float, seg: Int) {
        for (i in 0 until seg) {
            val a0 = i * 6.2831855f / seg; val a1 = (i + 1) * 6.2831855f / seg
            val y0 = y + cos(a0) * r; val z0 = z + sin(a0) * r
            val y1 = y + cos(a1) * r; val z1 = z + sin(a1) * r
            quad(x - halfW, y0, z0, x + halfW, y0, z0, x + halfW, y1, z1, x - halfW, y1, z1, x, y, z)
            tri(x + halfW, y, z, x + halfW, y0, z0, x + halfW, y1, z1, x - 1f, y, z)
            tri(x - halfW, y, z, x - halfW, y0, z0, x - halfW, y1, z1, x + 1f, y, z)
        }
    }

    /** Vertical cylinder (trunks, poles). */
    fun cylinderY(x: Float, y0: Float, y1: Float, z: Float, r: Float, seg: Int, caps: Boolean = true) {
        val ym = (y0 + y1) / 2
        for (i in 0 until seg) {
            val a0 = i * 6.2831855f / seg; val a1 = (i + 1) * 6.2831855f / seg
            val x0 = x + cos(a0) * r; val z0 = z + sin(a0) * r
            val x1 = x + cos(a1) * r; val z1 = z + sin(a1) * r
            quad(x0, y0, z0, x1, y0, z1, x1, y1, z1, x0, y1, z0, x, ym, z)
            if (caps) tri(x, y1, z, x0, y1, z0, x1, y1, z1, x, ym, z)
        }
    }

    /** Vertical cone (pines, traffic cones). */
    fun cone(x: Float, y0: Float, y1: Float, z: Float, r: Float, seg: Int) {
        val ym = y0 + (y1 - y0) * 0.3f
        for (i in 0 until seg) {
            val a0 = i * 6.2831855f / seg; val a1 = (i + 1) * 6.2831855f / seg
            tri(x + cos(a0) * r, y0, z + sin(a0) * r, x + cos(a1) * r, y0, z + sin(a1) * r, x, y1, z, x, ym, z)
            tri(x + cos(a0) * r, y0, z + sin(a0) * r, x + cos(a1) * r, y0, z + sin(a1) * r, x, y0, z, x, y0 + 1f, z)
        }
    }

    /** Low-poly ball (octahedron subdivided once, jittered for an organic look). */
    fun blob(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, rings: Int = 4, seg: Int = 7) {
        for (i in 0 until rings) {
            val t0 = i * 3.1415927f / rings - 1.5707964f
            val t1 = (i + 1) * 3.1415927f / rings - 1.5707964f
            for (j in 0 until seg) {
                val p0 = j * 6.2831855f / seg; val p1 = (j + 1) * 6.2831855f / seg
                val ax = x + cos(t0) * cos(p0) * rx; val ay = y + sin(t0) * ry; val az = z + cos(t0) * sin(p0) * rz
                val bx = x + cos(t0) * cos(p1) * rx; val bz = z + cos(t0) * sin(p1) * rz
                val cx = x + cos(t1) * cos(p1) * rx; val cy = y + sin(t1) * ry; val cz = z + cos(t1) * sin(p1) * rz
                val dx = x + cos(t1) * cos(p0) * rx; val dz = z + cos(t1) * sin(p0) * rz
                quad(ax, ay, az, bx, ay, bz, cx, cy, cz, dx, cy, dz, x, y, z)
            }
        }
    }

    fun toBuffer(): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data, 0, size).flip()
        return fb
    }

    companion object {
        const val FLOATS = 10
        const val STRIDE = FLOATS * 4
    }
}

/** A static or streamed VBO + VAO in the lit vertex format. */
class Mesh(private val capacityFloats: Int = 0) {
    var vao = 0; private set
    var vbo = 0; private set
    var vertexCount = 0; private set
    private var staging: FloatBuffer? = null

    fun uploadStatic(gl: GL, b: MeshBuilder) {
        create(gl)
        gl.bufferData(GLC.ARRAY_BUFFER, b.size * 4, b.toBuffer(), GLC.STATIC_DRAW)
        vertexCount = b.vertexCount
    }

    /** Dynamic mesh with fixed capacity; [update] reuses the same staging buffer. */
    fun createDynamic(gl: GL) {
        create(gl)
        gl.bufferData(GLC.ARRAY_BUFFER, capacityFloats * 4, null, GLC.DYNAMIC_DRAW)
        if (staging == null) {
            staging = ByteBuffer.allocateDirect(capacityFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
    }

    fun update(gl: GL, b: MeshBuilder) {
        val st = staging ?: return
        val n = minOf(b.size, capacityFloats)
        st.clear()
        st.put(b.data, 0, n)
        st.flip()
        gl.bindBuffer(GLC.ARRAY_BUFFER, vbo)
        gl.bufferSubData(GLC.ARRAY_BUFFER, 0, n * 4, st)
        vertexCount = n / MeshBuilder.FLOATS
    }

    private fun create(gl: GL) {
        vao = gl.genVertexArray()
        gl.bindVertexArray(vao)
        vbo = gl.genBuffer()
        gl.bindBuffer(GLC.ARRAY_BUFFER, vbo)
        gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(0, 3, MeshBuilder.STRIDE, 0)
        gl.enableVertexAttribArray(1)
        gl.vertexAttribPointer(1, 3, MeshBuilder.STRIDE, 12)
        gl.enableVertexAttribArray(2)
        gl.vertexAttribPointer(2, 4, MeshBuilder.STRIDE, 24)
        gl.bindVertexArray(0)
    }

    fun draw(gl: GL) {
        if (vertexCount == 0) return
        gl.bindVertexArray(vao)
        gl.drawArrays(GLC.TRIANGLES, 0, vertexCount)
    }
}
