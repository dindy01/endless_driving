package com.endlessdrive.core

/**
 * Gameplay tuning. Units: metres, seconds, m/s. Gameplay runs in a *road frame*:
 * `s` = distance along the road centreline, `x` = lateral offset (+ = right). Curves and hills
 * are purely visual (the renderer bends the road frame), so the rules stay simple and testable.
 */
object GameConfig {
    // ---- Road geometry (two lanes, same direction, shoulders, guard rails) ----
    const val LANE_W = 3.5f
    const val ROAD_HALF = 3.5f // paved half-width (two lanes)
    const val SHOULDER_W = 2.6f
    const val SHOULDER_OUTER = ROAD_HALF + SHOULDER_W // 6.1
    const val RAIL_INNER = 6.3f // guard rail face
    const val LANE0_X = -1.75f
    const val LANE1_X = 1.75f
    fun laneX(lane: Int): Float = when (lane) {
        0 -> LANE0_X
        1 -> LANE1_X
        -1 -> -4.8f // left shoulder
        else -> 4.8f // right shoulder
    }

    // ---- Player car ----
    const val CAR_HALF_W = 0.92f
    const val CAR_HALF_L = 2.2f
    const val HIT_SHRINK_W = 0.9f
    const val HIT_SHRINK_L = 0.95f

    // ---- Speed (difficulty curve; see Difficulty) ----
    const val V0 = 25f // 90 km/h
    const val V1 = 45f // 162 km/h cap of base speed
    const val SPEED_TAU = 90f
    const val BOOST_MULT = 1.3f
    const val V_MAX_PLAYER = V1 * BOOST_MULT // 58.5 m/s absolute player maximum
    const val BOOST_TIME = 2.5f
    const val BOOST_COOLDOWN = 8f
    const val BRAKE_MIN_SPEED = 12f
    const val ACCEL = 7f
    const val BRAKE_DECEL = 16f
    const val COAST_DECEL = 5f
    const val SHOULDER_SPEED_FACTOR = 0.6f
    const val SHOULDER_DECEL = 12f

    // ---- Steering ----
    const val LAT_MAX = 6.5f // max lateral speed (m/s), same at every speed
    const val STEER_TAU_SLOW = 0.10f // response time constant at low speed
    const val STEER_TAU_FAST = 0.22f // ... at top speed (less twitchy)
    const val STEER_EXPO_FAST = 0.8f // extra input expo at top speed (finer centre control)

    // ---- Difficulty ----
    const val GRACE_TIME = 15f
    const val DIFF_TAU = 80f
    const val DIFF_CAP = 0.95f
    const val SPACING_EASY = 95f // player travel between spawn events at level 0
    const val SPACING_HARD = 38f // ... at level 1
    const val SPACING_GRACE = 140f

    // ---- Traffic fairness invariants (see DECISIONS.md §4) ----
    /** Lateral speed the guarantee assumes (below what the car really achieves). */
    const val LAT_GUARANTEE = 4.0f
    /** Minimum edge-to-edge gap along the road between any two objects in DIFFERENT lanes. */
    const val GAP_OTHER_LANE = 65f
    /** Minimum headway between objects in the SAME lane. */
    const val GAP_SAME_LANE = 18f
    const val SHOULDER_SPACING = 40f

    // ---- Spawning ----
    const val SPAWN_AHEAD = 340f // beyond the maximum draw distance (300 m): no pop-in
    const val PREPOPULATE_FROM = 110f
    const val DESPAWN_BEHIND = 15f
    const val MAX_TRAFFIC = 48
    const val MAX_PICKUPS = 48
    const val FIRST_SHIELD_TIME = 18f
    const val SHIELD_MIN_INTERVAL = 25f
    const val SHIELD_MAX_INTERVAL = 40f

    // ---- Effects ----
    const val SHIELD_TIME = 5f
    const val SHIELD_BREAK_INVULN = 0.8f
    const val CONTINUE_INVULN = 2.5f
    const val CONTINUE_CLEAR_AHEAD = 140f

    // ---- Scoring ----
    const val POINTS_PER_METRE = 1f
    const val COIN_POINTS = 25
    const val NEAR_MISS_POINTS = 50
    const val NEAR_MISS_GAP = 1.0f // edge-to-edge lateral gap (m)
    const val CLOSE_CALL_GAP = 0.4f
    const val NEAR_MISS_MIN_SPEED = 20f
    const val COMBO_WINDOW = 3f
    const val MAX_COMBO = 8
    const val PICKUP_RADIUS = 0.7f

    const val KMH_PER_MS = 3.6f
}
