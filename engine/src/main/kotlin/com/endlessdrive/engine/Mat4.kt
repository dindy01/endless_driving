package com.endlessdrive.engine

import kotlin.math.sqrt
import kotlin.math.tan

/** Column-major 4x4 helpers on FloatArray(16). Nothing here allocates. */
object Mat4 {
    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    fun perspective(m: FloatArray, fovYDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovYDeg * 0.008726646f)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
    }

    /** out = a * b (out must not alias a or b). */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) {
            val b0 = b[c * 4]; val b1 = b[c * 4 + 1]; val b2 = b[c * 4 + 2]; val b3 = b[c * 4 + 3]
            out[c * 4] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
            out[c * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
            out[c * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
            out[c * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
        }
    }

    /**
     * Rotation-only view matrix for a camera at the origin looking along (fx,fy,fz) (need not be
     * normalised), with world up +y and a roll angle given by (rollSin, rollCos).
     * Also writes the camera's right / up / forward unit vectors into [basis] (9 floats).
     */
    fun viewRotation(m: FloatArray, fx: Float, fy: Float, fz: Float, rollSin: Float, rollCos: Float, basis: FloatArray) {
        var l = 1f / sqrt(fx * fx + fy * fy + fz * fz)
        val dx = fx * l; val dy = fy * l; val dz = fz * l
        // right = f x up(0,1,0)
        var rx = -dz; var ry = 0f; var rz = dx
        l = 1f / sqrt(rx * rx + rz * rz)
        rx *= l; rz *= l
        // up = r x f
        var ux = ry * dz - rz * dy
        var uy = rz * dx - rx * dz
        var uz = rx * dy - ry * dx
        // roll about the forward axis
        val r2x = rx * rollCos + ux * rollSin; val r2y = ry * rollCos + uy * rollSin; val r2z = rz * rollCos + uz * rollSin
        ux = ux * rollCos - rx * rollSin; uy = uy * rollCos - ry * rollSin; uz = uz * rollCos - rz * rollSin
        rx = r2x; ry = r2y; rz = r2z
        m[0] = rx; m[4] = ry; m[8] = rz; m[12] = 0f
        m[1] = ux; m[5] = uy; m[9] = uz; m[13] = 0f
        m[2] = -dx; m[6] = -dy; m[10] = -dz; m[14] = 0f
        m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        basis[0] = rx; basis[1] = ry; basis[2] = rz
        basis[3] = ux; basis[4] = uy; basis[5] = uz
        basis[6] = dx; basis[7] = dy; basis[8] = dz
    }

    fun translation(m: FloatArray, x: Float, y: Float, z: Float) {
        identity(m)
        m[12] = x; m[13] = y; m[14] = z
    }

    /**
     * Model matrix for an object on the road: model -z is "forward".
     * Forward = (sinH, slope, -cosH) normalised, right is horizontal, optional roll (radians, small) and scale.
     */
    fun roadObject(
        m: FloatArray, x: Float, y: Float, z: Float, sinH: Float, cosH: Float, slope: Float, roll: Float,
        sx: Float, sy: Float, sz: Float,
    ) {
        val l = 1f / sqrt(1f + slope * slope)
        val fx = sinH * l; val fy = slope * l; val fz = -cosH * l
        var rx = cosH; var ry = 0f; var rz = sinH
        // up = back x right = (-f) x r
        var ux = -fy * rz + fz * ry
        var uy = -fz * rx + fx * rz
        var uz = -fx * ry + fy * rx
        if (roll != 0f) {
            val c = 1f - roll * roll * 0.5f
            val s = roll
            val nrx = rx * c + ux * s; val nry = ry * c + uy * s; val nrz = rz * c + uz * s
            ux = ux * c - rx * s; uy = uy * c - ry * s; uz = uz * c - rz * s
            rx = nrx; ry = nry; rz = nrz
        }
        m[0] = rx * sx; m[1] = ry * sx; m[2] = rz * sx; m[3] = 0f
        m[4] = ux * sy; m[5] = uy * sy; m[6] = uz * sy; m[7] = 0f
        m[8] = -fx * sz; m[9] = -fy * sz; m[10] = -fz * sz; m[11] = 0f
        m[12] = x; m[13] = y; m[14] = z; m[15] = 1f
    }
}
