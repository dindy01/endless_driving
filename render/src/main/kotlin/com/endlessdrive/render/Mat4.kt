package com.endlessdrive.render

import kotlin.math.sqrt
import kotlin.math.tan

/** Column-major 4x4 matrix helpers on FloatArray(16). No allocation. */
object Mat4 {
    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    fun perspective(m: FloatArray, fovYRad: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovYRad / 2f)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
    }

    /** View matrix for an eye at (ex,ey,ez) looking at (cx,cy,cz) with world up (0,1,0). */
    fun lookAt(m: FloatArray, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float) {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        var l = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= l; fy /= l; fz /= l
        // s = f x up(0,1,0)
        var sx = -fz; val sy = 0f; var sz = fx
        l = sqrt(sx * sx + sz * sz)
        sx /= l; sz /= l
        // u = s x f
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx
        m[0] = sx; m[4] = sy; m[8] = sz
        m[1] = ux; m[5] = uy; m[9] = uz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(ux * ex + uy * ey + uz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
    }

    /** out = a * b (out must not alias a or b). */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
            }
        }
    }

    /**
     * Model matrix from a forward vector (fx,fy,fz) and approximate up (0,1,0), uniform-ish scale
     * (sx, sy, sz) and translation. Basis is right-handed: X = up × Z, Y = Z × X.
     */
    fun model(
        m: FloatArray, fx: Float, fy: Float, fz: Float, tx: Float, ty: Float, tz: Float,
        sx: Float = 1f, sy: Float = 1f, sz: Float = 1f,
    ) {
        var l = sqrt(fx * fx + fy * fy + fz * fz)
        val zx = fx / l; val zy = fy / l; val zz = fz / l
        // X = up(0,1,0) × Z = (zz, 0, -zx)
        var xx = zz; var xz = -zx
        l = sqrt(xx * xx + xz * xz)
        xx /= l; xz /= l
        // Y = Z × X
        val yx = zy * xz - zz * 0f
        val yy = zz * xx - zx * xz
        val yz = zx * 0f - zy * xx
        m[0] = xx * sx; m[1] = 0f; m[2] = xz * sx; m[3] = 0f
        m[4] = yx * sy; m[5] = yy * sy; m[6] = yz * sy; m[7] = 0f
        m[8] = zx * sz; m[9] = zy * sz; m[10] = zz * sz; m[11] = 0f
        m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
    }

    fun translation(m: FloatArray, tx: Float, ty: Float, tz: Float) {
        identity(m)
        m[12] = tx; m[13] = ty; m[14] = tz
    }

    /** Transforms point (x,y,z,1) by m; writes clip coords into out[0..3]. */
    fun transform(out: FloatArray, m: FloatArray, x: Float, y: Float, z: Float) {
        for (r in 0 until 4) out[r] = m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r]
    }
}
