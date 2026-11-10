package com.endlessdrive.core

/**
 * All gameplay tuning constants in one place. Units: 1 world unit = 1 lane width.
 * z grows "up the road"; the player drives toward +z.
 */
object GameConfig {
    const val LANES = 3
    const val LANE_W = 1f

    const val PLAYER_HALF_W = 0.27f
    const val PLAYER_HALF_L = 0.56f

    /** Hitboxes are shrunk for fairness (collisions feel "deserved"). */
    const val HITBOX_SHRINK = 0.85f

    // --- Speed / difficulty (see Difficulty + DECISIONS.md) ---
    const val V_MIN = 6f
    const val V_MAX = 12f
    const val SPEED_TAU = 75f
    const val GRACE_TIME = 15f
    const val DIFF_TAU = 75f
    const val DIFF_CAP = 0.95f
    /** Free road (world units) between consecutive obstacle rows at difficulty 0 and 1. */
    const val GAP_EASY = 6.5f
    const val GAP_HARD = 2.6f

    // --- Lane changes / input ---
    const val LANE_CHANGE_TIME = 0.12f
    const val LANE_SHIFT_MARGIN = 0.3f
    const val INPUT_BUFFER_TTL = 0.35f
    const val INPUT_BUFFER_SIZE = 8

    // --- Spawning ---
    const val SPAWN_AHEAD = 12f
    const val DESPAWN_BEHIND = 3f
    const val FIRST_ROW_EXTRA = 2f
    const val MAX_OBSTACLES = 48
    const val MAX_PICKUPS = 64

    // --- Effects ---
    const val OIL_LOCK_TIME = 0.6f
    const val SHIELD_TIME = 5f
    const val SHIELD_BREAK_INVULN = 0.6f
    const val CONTINUE_INVULN = 2f
    const val SHIELD_MIN_INTERVAL = 25f
    const val SHIELD_MAX_INTERVAL = 40f
    const val FIRST_SHIELD_TIME = 20f

    // --- Scoring ---
    const val POINTS_PER_UNIT = 2f
    const val COIN_POINTS = 10
    const val NEAR_MISS_POINTS = 25
    /** Edge-to-edge lateral gap under which a pass counts as a near miss (= adjacent lane). */
    const val NEAR_MISS_MARGIN = 0.5f * LANE_W
    /** Edge gap under which a near miss is a "close call" (double points). */
    const val CLOSE_CALL_GAP = 0.2f
    const val COMBO_WINDOW = 2.5f
    const val MAX_COMBO = 5
    const val PICKUP_RADIUS = 0.3f

    /** Display conversion: world units/s -> km/h shown on the HUD. */
    const val KMH_PER_UNIT = 18f

    fun laneX(lane: Int): Float = (lane - 1) * LANE_W
}
