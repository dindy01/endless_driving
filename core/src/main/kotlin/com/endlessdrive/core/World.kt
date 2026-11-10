package com.endlessdrive.core

import com.endlessdrive.core.GameConfig.MAX_OBSTACLES
import com.endlessdrive.core.GameConfig.MAX_PICKUPS
import com.endlessdrive.core.GameConfig.PLAYER_HALF_L
import kotlin.math.abs

class Obstacle {
    @JvmField var active = false
    @JvmField var kind = 0
    @JvmField var lane = 0
    @JvmField var x = 0f
    @JvmField var z = 0f
    @JvmField var passed = false
    @JvmField var triggered = false
    @JvmField var minGap = Float.MAX_VALUE
    /** Per-instance cosmetic variation (colour index etc.). */
    @JvmField var variant = 0
}

class Pickup {
    @JvmField var active = false
    @JvmField var kind = 0
    @JvmField var lane = 0
    @JvmField var x = 0f
    @JvmField var z = 0f
}

/** Callbacks for audio / particles / haptics. All default to no-ops. */
interface GameEvents {
    fun onLaneChange(dir: Int) {}
    fun onCoin(x: Float, z: Float) {}
    fun onShieldPickup(x: Float, z: Float) {}
    fun onShieldBreak(x: Float, z: Float) {}
    fun onOil(x: Float, z: Float) {}
    fun onNearMiss(x: Float, z: Float, combo: Int, points: Int, closeCall: Boolean) {}
    fun onCrash(x: Float, z: Float) {}

    object None : GameEvents
}

/**
 * The whole simulation. Rendering reads its public state; nothing here touches Android.
 * update() performs no allocations.
 */
class World(seed: Long = 1L, var events: GameEvents = GameEvents.None) : SpawnSink {
    private val rng = Rng(seed)
    val spawner = Spawner(rng)
    val lane = LaneController()
    val obstacles = Array(MAX_OBSTACLES) { Obstacle() }
    val pickups = Array(MAX_PICKUPS) { Pickup() }

    var time = 0f
        private set
    var distance = 0f
        private set
    var speed = GameConfig.V_MIN
        private set
    var bonus = 0L
        private set
    var coins = 0
        private set
    var nearMisses = 0
        private set
    var combo = 0
        private set
    var comboTimer = 0f
        private set
    var shieldTimer = 0f
        private set
    var invulnTimer = 0f
        private set
    var alive = true
        private set
    var droppedSpawns = 0
        private set
    var crashX = 0f
        private set
    var crashZ = 0f
        private set

    val playerX: Float get() = lane.x
    val score: Long get() = Scoring.total(distance, bonus)
    val level: Float get() = Difficulty.level(time)

    init {
        reset(seed)
    }

    fun reset(seed: Long) {
        rng.setSeed(seed)
        time = 0f
        distance = 0f
        speed = Difficulty.speed(0f)
        bonus = 0L
        coins = 0
        nearMisses = 0
        combo = 0
        comboTimer = 0f
        shieldTimer = 0f
        invulnTimer = 0f
        alive = true
        droppedSpawns = 0
        lane.reset(1)
        for (o in obstacles) o.active = false
        for (p in pickups) p.active = false
        spawner.reset(0f)
    }

    fun requestLane(dir: Int) {
        if (alive) lane.request(dir, time)
    }

    /** Continue after a crash (rewarded ad): clears the road ahead and grants brief invulnerability. */
    fun revive() {
        if (alive) return
        for (o in obstacles) o.active = false
        for (p in pickups) p.active = false
        spawner.clearAhead(distance)
        invulnTimer = GameConfig.CONTINUE_INVULN
        combo = 0
        comboTimer = 0f
        lane.reset(lane.lane)
        alive = true
    }

    fun update(dtIn: Float) {
        if (!alive) return
        val dt = if (dtIn > 0.05f) 0.05f else if (dtIn < 0f) 0f else dtIn
        time += dt
        speed = Difficulty.speed(time)

        val started = lane.update(dt, time)
        if (started != 0) events.onLaneChange(started)

        distance += speed * dt
        spawner.update(distance, speed, time, this)

        if (shieldTimer > 0f) shieldTimer = (shieldTimer - dt).coerceAtLeast(0f)
        if (invulnTimer > 0f) invulnTimer = (invulnTimer - dt).coerceAtLeast(0f)
        if (comboTimer > 0f) {
            comboTimer -= dt
            if (comboTimer <= 0f) {
                comboTimer = 0f
                combo = 0
            }
        }

        val px = lane.x
        val pz = distance
        for (i in obstacles.indices) {
            val o = obstacles[i]
            if (!o.active) continue
            o.z += ObstacleKind.SPEED[o.kind] * dt
            val halfL = ObstacleKind.HALF_L[o.kind]
            if (o.z + halfL < pz - GameConfig.DESPAWN_BEHIND) {
                o.active = false
                continue
            }
            if (!ObstacleKind.isSolid(o.kind)) {
                if (!o.triggered && Collision.playerHits(px, pz, o.kind, o.x, o.z)) {
                    o.triggered = true
                    lane.lock(GameConfig.OIL_LOCK_TIME)
                    events.onOil(px, pz)
                }
                continue
            }
            if (abs(o.z - pz) < halfL + PLAYER_HALF_L) {
                val g = Collision.lateralGap(px, o.kind, o.x)
                if (g < o.minGap) o.minGap = g
            }
            if (Collision.playerHits(px, pz, o.kind, o.x, o.z)) {
                if (invulnTimer > 0f) continue
                if (shieldTimer > 0f) {
                    shieldTimer = 0f
                    invulnTimer = GameConfig.SHIELD_BREAK_INVULN
                    o.active = false
                    events.onShieldBreak(o.x, o.z)
                    continue
                }
                alive = false
                crashX = px
                crashZ = pz
                combo = 0
                events.onCrash(px, pz)
                return
            }
            if (!o.passed && o.z + halfL < pz - PLAYER_HALF_L) {
                o.passed = true
                if (Scoring.isNearMiss(o.minGap)) {
                    combo = if (comboTimer > 0f) (combo + 1).coerceAtMost(GameConfig.MAX_COMBO) else 1
                    comboTimer = GameConfig.COMBO_WINDOW
                    val close = Scoring.isCloseCall(o.minGap)
                    val pts = Scoring.nearMissPoints(combo, close)
                    bonus += pts
                    nearMisses++
                    events.onNearMiss(o.x, o.z, combo, pts, close)
                }
            }
        }

        for (i in pickups.indices) {
            val p = pickups[i]
            if (!p.active) continue
            if (p.z < pz - GameConfig.DESPAWN_BEHIND) {
                p.active = false
                continue
            }
            if (abs(p.z - pz) < PLAYER_HALF_L + GameConfig.PICKUP_RADIUS &&
                abs(p.x - px) < GameConfig.PLAYER_HALF_W + GameConfig.PICKUP_RADIUS
            ) {
                p.active = false
                if (p.kind == PickupKind.COIN) {
                    coins++
                    bonus += GameConfig.COIN_POINTS
                    events.onCoin(p.x, p.z)
                } else {
                    shieldTimer = GameConfig.SHIELD_TIME
                    events.onShieldPickup(p.x, p.z)
                }
            }
        }
    }

    // ---- SpawnSink (pool allocation, no `new`) ----
    override fun spawnObstacle(kind: Int, lane: Int, z: Float) {
        for (o in obstacles) {
            if (o.active) continue
            o.active = true
            o.kind = kind
            o.lane = lane
            o.x = GameConfig.laneX(lane)
            o.z = z
            o.passed = false
            o.triggered = false
            o.minGap = Float.MAX_VALUE
            o.variant = rng.nextInt(8)
            return
        }
        droppedSpawns++
    }

    override fun spawnPickup(kind: Int, lane: Int, z: Float) {
        for (p in pickups) {
            if (p.active) continue
            p.active = true
            p.kind = kind
            p.lane = lane
            p.x = GameConfig.laneX(lane)
            p.z = z
            return
        }
        droppedSpawns++
    }

    /** Test helper: places an obstacle directly. */
    fun placeObstacleForTest(kind: Int, lane: Int, z: Float) = spawnObstacle(kind, lane, z)

    /** Test helper: places a pickup directly. */
    fun placePickupForTest(kind: Int, lane: Int, z: Float) = spawnPickup(kind, lane, z)

    /** Test helper: stops procedural spawning by pushing the next row far away. */
    fun disableSpawningForTest() = spawner.clearAhead(1e9f)
}
