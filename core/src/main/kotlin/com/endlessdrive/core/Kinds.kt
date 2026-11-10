package com.endlessdrive.core

/** Traffic/obstacle kinds as Int constants with lookup tables (no enum iteration allocations). */
object Kind {
    const val CAR = 0
    const val TRUCK = 1
    const val STOPPED_CAR = 2
    const val BARRIER = 3
    const val CONES = 4
    const val ROADWORKS = 5
    const val SHOULDER_CAR = 6
    const val COUNT = 7

    @JvmField val HALF_W = floatArrayOf(0.92f, 1.25f, 0.92f, 1.6f, 1.5f, 1.7f, 0.92f)
    /** Default half length; roadworks get a random length per instance. */
    @JvmField val HALF_L = floatArrayOf(2.2f, 5.5f, 2.2f, 0.35f, 2.5f, 25f, 2.2f)
    @JvmField val MOVING = booleanArrayOf(true, true, false, false, false, false, false)

    fun isLaneObject(kind: Int) = kind != SHOULDER_CAR
}

object PickupKind {
    const val COIN = 0
    const val SHIELD = 1
}
