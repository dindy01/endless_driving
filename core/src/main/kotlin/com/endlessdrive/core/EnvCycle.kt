package com.endlessdrive.core

/**
 * Day / weather cycle: CLEAR -> DUSK -> NIGHT -> RAIN -> ..., each phase ENV_PHASE_S long with a
 * smooth ENV_BLEND_S cross-fade at the start of each phase. Pure function of time.
 */
class EnvCycle {
    /** Blend weights of the four phases; they always sum to 1. */
    val w = FloatArray(4)
    var phase = CLEAR
        private set

    val dusk get() = w[DUSK]
    val night get() = w[NIGHT]
    val rain get() = w[RAIN]

    init {
        update(0f)
    }

    fun update(t: Float) {
        val tt = if (t < 0f) 0f else t
        val idx = (tt / Cfg.ENV_PHASE_S).toInt()
        val local = tt - idx * Cfg.ENV_PHASE_S
        val cur = idx % 4
        val prev = (idx + 3) % 4
        var b = if (idx == 0) 1f else (local / Cfg.ENV_BLEND_S).coerceIn(0f, 1f)
        b = b * b * (3f - 2f * b)
        w[0] = 0f; w[1] = 0f; w[2] = 0f; w[3] = 0f
        w[prev] += 1f - b
        w[cur] += b
        phase = cur
    }

    companion object {
        const val CLEAR = 0
        const val DUSK = 1
        const val NIGHT = 2
        const val RAIN = 3
    }
}
