package com.endlessdrive.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Day/weather cycle: CLEAR -> DUSK -> NIGHT -> RAIN (light rain, overcast) -> CLEAR ...
 * Each phase lasts [PHASE] seconds (75 s) with an eased [BLEND] second transition.
 * Produces lighting, sky and fog parameters by blending per-phase presets (no allocation).
 */
class Environment {
    val sunDir = FloatArray(3)
    val sunColor = FloatArray(3)
    val ambient = FloatArray(3)
    val zenith = FloatArray(3)
    val horizon = FloatArray(3)
    val fog = FloatArray(3)
    var night = 0f; private set
    var rain = 0f; private set
    var fogScale = 1f; private set
    var phase = 0; private set

    fun update(t: Float) {
        val cyc = ((t % (PHASE * 4)) + PHASE * 4) % (PHASE * 4)
        val idx = (cyc / PHASE).toInt() % 4
        val within = cyc - idx * PHASE
        val next = (idx + 1) % 4
        var k = ((within - (PHASE - BLEND)) / BLEND).coerceIn(0f, 1f)
        k = k * k * (3f - 2f * k)
        phase = if (k > 0.5f) next else idx
        blend3(sunColor, SUN_COLOR, idx, next, k)
        blend3(ambient, AMBIENT, idx, next, k)
        blend3(zenith, ZENITH, idx, next, k)
        blend3(horizon, HORIZON, idx, next, k)
        blend3(fog, FOG, idx, next, k)
        night = lerp(NIGHT[idx], NIGHT[next], k)
        rain = lerp(RAIN[idx], RAIN[next], k)
        fogScale = lerp(FOG_SCALE[idx], FOG_SCALE[next], k)
        val elev = lerp(SUN_ELEV[idx], SUN_ELEV[next], k)
        val az = 0.6f + t * 0.002f
        sunDir[0] = cos(elev) * sin(az)
        sunDir[1] = sin(elev)
        sunDir[2] = cos(elev) * cos(az)
        val l = sqrt(sunDir[0] * sunDir[0] + sunDir[1] * sunDir[1] + sunDir[2] * sunDir[2])
        sunDir[0] /= l; sunDir[1] /= l; sunDir[2] /= l
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun blend3(out: FloatArray, table: FloatArray, a: Int, b: Int, k: Float) {
        for (c in 0 until 3) out[c] = lerp(table[a * 3 + c], table[b * 3 + c], k)
    }

    companion object {
        const val PHASE = 75f
        const val BLEND = 8f
        const val CLEAR = 0
        const val DUSK = 1
        const val NIGHT_PHASE = 2
        const val RAIN_PHASE = 3
        private val SUN_COLOR = floatArrayOf(1.0f, 0.95f, 0.85f, 1.0f, 0.55f, 0.3f, 0.12f, 0.14f, 0.22f, 0.45f, 0.47f, 0.5f)
        private val AMBIENT = floatArrayOf(0.52f, 0.56f, 0.62f, 0.42f, 0.36f, 0.42f, 0.11f, 0.13f, 0.2f, 0.42f, 0.44f, 0.48f)
        private val ZENITH = floatArrayOf(0.25f, 0.5f, 0.88f, 0.22f, 0.22f, 0.45f, 0.01f, 0.02f, 0.07f, 0.38f, 0.41f, 0.46f)
        private val HORIZON = floatArrayOf(0.72f, 0.85f, 0.95f, 0.98f, 0.55f, 0.35f, 0.04f, 0.06f, 0.12f, 0.6f, 0.62f, 0.65f)
        private val FOG = floatArrayOf(0.72f, 0.83f, 0.93f, 0.85f, 0.55f, 0.42f, 0.03f, 0.04f, 0.09f, 0.55f, 0.57f, 0.6f)
        private val NIGHT = floatArrayOf(0f, 0.35f, 1f, 0.25f)
        private val RAIN = floatArrayOf(0f, 0f, 0f, 1f)
        private val FOG_SCALE = floatArrayOf(1f, 1.1f, 1.2f, 1.9f)
        private val SUN_ELEV = floatArrayOf(0.85f, 0.12f, 0.45f, 0.7f)
    }
}
