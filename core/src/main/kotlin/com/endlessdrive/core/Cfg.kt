package com.endlessdrive.core

/** All gameplay tuning in one place. Units: metres, seconds, m/s. */
object Cfg {
    // --- road geometry ---
    const val LANE_W = 3.5f
    const val ROAD_HALF = 3.5f            // two lanes, centreline at x = 0
    const val SHOULDER_W = 2.3f
    const val RAIL_X = ROAD_HALF + SHOULDER_W  // inner face of the guard rail
    const val LANE_LEFT_X = -1.75f
    const val LANE_RIGHT_X = 1.75f

    // --- player car ---
    const val PLAYER_HALF_W = 0.92f
    const val PLAYER_HALF_L = 2.1f
    const val START_S = 40.0

    // --- speed / difficulty curve (see Difficulty) ---
    const val V0 = 25f                    // cruise speed at t = 0 (90 km/h)
    const val VMAX = 55f                  // cruise cap (198 km/h)
    const val TAU_V = 80f
    const val GRACE_S = 15f               // forgiving period
    const val TAU_LEVEL = 70f
    const val LEVEL_CAP = 0.95f
    const val NITRO_MULT = 1.3f
    const val SLOW_FACTOR = 0.62f         // brake floor and shoulder speed, relative to cruise
    const val TRAFFIC_MIN_FACTOR = 0.34f  // traffic speed range relative to cruise at spawn
    const val TRAFFIC_MAX_FACTOR = 0.58f  // must stay below SLOW_FACTOR: nothing ever catches the player

    const val ACCEL = 7f
    const val ACCEL_NITRO = 20f
    const val DECEL_COAST = 8f
    const val DECEL_SHOULDER = 14f
    const val DECEL_BRAKE = 26f

    // --- nitro ---
    const val NITRO_DURATION = 2.5f
    const val NITRO_COOLDOWN = 7f

    // --- steering ---
    const val LAT_SPEED_LOW = 6.0f        // max lateral speed at V0
    const val LAT_SPEED_HIGH = 7.5f       // max lateral speed at VMAX * NITRO_MULT
    const val STEER_TAU_LOW = 0.06f       // input smoothing time constant at low speed
    const val STEER_TAU_HIGH = 0.13f      // ... at top speed (less twitchy)
    const val DRIFT_K = 0.085f            // curve push = DRIFT_K * curvature * v^2
    const val DRIFT_MAX = 1.2f

    // --- spawner ---
    const val SPAWN_AHEAD = 430f          // must exceed the largest draw distance
    const val START_CLEAR = 95f
    const val DESPAWN_BEHIND = 30f
    const val T_STRUCT = 0.6f             // hard minimum time between forced lane changes
    const val GAP_TIME_EASY = 2.3f        // comfortable time between forced lane changes, level 0
    const val GAP_TIME_HARD = 0.95f       // ... at level cap
    const val FOLLOW_GAP = 7f
    const val AI_K = 1.2f

    // --- scoring ---
    const val COIN_POINTS = 50
    const val NEAR_MISS_POINTS = 100
    const val NEAR_MISS_DIST = 0.9f       // edge-to-edge lateral gap
    const val NEAR_MISS_MIN_REL_SPEED = 8f
    const val COMBO_WINDOW = 3.5f
    const val COMBO_MAX = 8

    // --- pickups / shield ---
    const val SHIELD_DURATION = 5f
    const val INVULN_AFTER_SHIELD = 1.0f
    const val INVULN_AFTER_REVIVE = 2.5f

    // --- crash ---
    const val CRASH_DURATION = 1.4f       // real seconds
    const val CRASH_TIME_SCALE = 0.25f

    // --- environment ---
    const val ENV_PHASE_S = 75f
    const val ENV_BLEND_S = 8f

    const val KMH = 3.6f
}

object Kind {
    const val CAR = 0
    const val VAN = 1
    const val TRUCK = 2
    const val CONE = 3
    const val BARRIER = 4
    const val SIGN = 5
    const val COUNT = 6

    val HALF_W = floatArrayOf(0.95f, 1.05f, 1.25f, 0.28f, 1.45f, 1.0f)
    val HALF_L = floatArrayOf(2.1f, 2.5f, 4.6f, 0.28f, 0.35f, 0.45f)
    fun isVehicle(k: Int) = k <= TRUCK
}

object Ev {
    const val COIN = 1
    const val SHIELD = 2
    const val SHIELD_BREAK = 4
    const val NEAR_MISS = 8
    const val CRASH = 16
    const val NITRO = 32
    const val GAME_OVER = 64
    const val NITRO_READY = 128
}
