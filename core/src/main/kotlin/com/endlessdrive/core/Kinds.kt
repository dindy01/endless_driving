package com.endlessdrive.core

/** Obstacle kinds. Plain Int constants + lookup tables avoid enum iteration allocations. */
object ObstacleKind {
    const val SLOW_CAR = 0
    const val BARRIER = 1
    const val OIL = 2
    const val TRUCK = 3
    const val COUNT = 4

    @JvmField val HALF_W = floatArrayOf(0.27f, 0.42f, 0.36f, 0.37f)
    @JvmField val HALF_L = floatArrayOf(0.56f, 0.16f, 0.42f, 1.25f)
    /** Speed along the road (world units/s). Always far below [GameConfig.V_MIN]. */
    @JvmField val SPEED = floatArrayOf(2.2f, 0f, 0f, 1.4f)

    const val MAX_HALF_L = 1.25f

    /** Solid obstacles end the run on contact; oil only locks steering. */
    fun isSolid(kind: Int): Boolean = kind != OIL
}

object PickupKind {
    const val COIN = 0
    const val SHIELD = 1
}
