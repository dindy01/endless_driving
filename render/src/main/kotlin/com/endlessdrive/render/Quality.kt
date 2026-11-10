package com.endlessdrive.render

/** Graphics presets: scale draw distance, shadows, particles, scenery and weather density. */
class Quality private constructor(
    val level: Int,
    val drawDistance: Float,
    val shadows: Boolean,
    val particleLimit: Int,
    val sceneryDensity: Float,
    val rainDrops: Int,
    val glows: Boolean,
) {
    companion object {
        const val LOW = 0
        const val MEDIUM = 1
        const val HIGH = 2
        val PRESETS = arrayOf(
            Quality(LOW, 150f, false, 120, 0.45f, 160, false),
            Quality(MEDIUM, 220f, true, 260, 0.75f, 320, true),
            Quality(HIGH, 300f, true, 400, 1f, 520, true),
        )

        fun of(level: Int): Quality = PRESETS[level.coerceIn(0, 2)]
    }
}
