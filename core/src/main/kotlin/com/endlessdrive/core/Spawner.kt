package com.endlessdrive.core

import com.endlessdrive.core.GameConfig.LANES
import com.endlessdrive.core.GameConfig.LANE_CHANGE_TIME
import com.endlessdrive.core.GameConfig.LANE_SHIFT_MARGIN
import com.endlessdrive.core.GameConfig.PLAYER_HALF_L
import com.endlessdrive.core.GameConfig.SPAWN_AHEAD
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

interface SpawnSink {
    fun spawnObstacle(kind: Int, lane: Int, z: Float)
    fun spawnPickup(kind: Int, lane: Int, z: Float)
}

/**
 * Generates obstacle "rows". Invariants that make every pattern passable:
 *
 *  1. Rows never overlap in z: each row starts after [horizon], the furthest z any previously
 *     spawned obstacle can occupy *when the player reaches it* (moving vehicles are extended by
 *     their worst-case forward drift). So between rows the whole road is empty.
 *  2. A row blocks at most 2 of the 3 lanes.
 *  3. Fairness: from every lane that was free in the previous row, some free lane of the new row
 *     is reachable using at most `shifts` lane changes, where `shifts` is how many full lane
 *     changes fit into the empty gap at the (upper bound of the) speed the player will have.
 *
 * Oil is treated as blocking for these guarantees: a clean line always avoids it.
 */
class Spawner(private val rng: Rng) {
    var nextRowZ = 0f
        private set
    var horizon = 0f
        private set
    var prevFreeMask = 0b111
        private set
    var safeLane = 1
        private set
    var rowsSpawned = 0
        private set

    private var nextShieldTime = GameConfig.FIRST_SHIELD_TIME
    private val validSingles = IntArray(3)
    private val validDoubles = IntArray(3)

    fun reset(startZ: Float) {
        horizon = startZ
        nextRowZ = startZ + SPAWN_AHEAD + GameConfig.FIRST_ROW_EXTRA
        prevFreeMask = 0b111
        safeLane = 1
        rowsSpawned = 0
        nextShieldTime = GameConfig.FIRST_SHIELD_TIME
    }

    /** Used by "continue": wipe the road ahead and restart spawning just off-screen. */
    fun clearAhead(playerZ: Float) {
        horizon = playerZ + 1f
        nextRowZ = playerZ + SPAWN_AHEAD + 1f
        prevFreeMask = 0b111
    }

    fun update(playerZ: Float, speed: Float, time: Float, sink: SpawnSink) {
        while (nextRowZ <= playerZ + SPAWN_AHEAD) spawnRow(playerZ, speed, time, sink)
    }

    /** Max lane changes that fit in a free stretch of [freeLen] at speed [v]. */
    fun shiftsFor(freeLen: Float, v: Float): Int {
        if (freeLen <= 0f) return 0
        val perShift = v * LANE_CHANGE_TIME + LANE_SHIFT_MARGIN
        return min(LANES - 1, (freeLen / perShift).toInt())
    }

    private fun spawnRow(playerZ: Float, speed: Float, time: Float, sink: SpawnSink) {
        val rowZ = nextRowZ
        val level = Difficulty.level(time)
        val grace = Difficulty.inGrace(time)

        // Speed only increases, so arrival is no later than distance/currentSpeed: bound vEnc.
        val tArrive = time + max(0f, rowZ - playerZ) / speed
        val vEnc = Difficulty.speed(tArrive)
        val free = (rowZ - ObstacleKind.MAX_HALF_L) - horizon - 2f * PLAYER_HALF_L
        val shifts = shiftsFor(free, vEnc)

        val mask = chooseMask(shifts, if (grace) 0f else Difficulty.doubleBlockChance(level))
        val freeMask = mask.inv() and 0b111
        safeLane = pickSafeLane(freeMask)

        val prevHorizon = horizon
        var rowEnd = rowZ
        for (lane in 0 until LANES) {
            if (mask and (1 shl lane) == 0) continue
            val kind = Difficulty.pickKind(level, grace, rng.nextFloat())
            sink.spawnObstacle(kind, lane, rowZ)
            val u = ObstacleKind.SPEED[kind]
            // Worst-case forward drift before the player reaches it (player speed >= current).
            val drift = if (u > 0f) u * max(0f, rowZ - playerZ) / (speed - u) else 0f
            rowEnd = max(rowEnd, rowZ + ObstacleKind.HALF_L[kind] + drift)
        }
        horizon = max(horizon, rowEnd)

        spawnPickups(rowZ, prevHorizon, time, mask != 0, sink)

        prevFreeMask = freeMask
        rowsSpawned++
        nextRowZ = horizon + ObstacleKind.MAX_HALF_L + 2f * PLAYER_HALF_L +
            Difficulty.gap(level) * rng.range(1f, 1.45f)
    }

    private fun spawnPickups(rowZ: Float, gapStart: Float, time: Float, hasObstacles: Boolean, sink: SpawnSink) {
        if (hasObstacles && time >= nextShieldTime) {
            sink.spawnPickup(PickupKind.SHIELD, safeLane, rowZ)
            nextShieldTime = time + rng.range(GameConfig.SHIELD_MIN_INTERVAL, GameConfig.SHIELD_MAX_INTERVAL)
            return
        }
        if (!rng.chance(0.6f)) return
        val count = 3 + rng.nextInt(3)
        for (i in 0 until count) {
            val z = rowZ - i * 0.65f
            if (z < gapStart + 0.4f) break
            sink.spawnPickup(PickupKind.COIN, safeLane, z)
        }
    }

    private fun chooseMask(shifts: Int, p2: Float): Int {
        var ns = 0
        var nd = 0
        for (b in 0 until LANES) {
            val single = 1 shl b
            if (isFair(single, shifts)) validSingles[ns++] = single
            val double = 0b111 xor single
            if (p2 > 0f && isFair(double, shifts)) validDoubles[nd++] = double
        }
        if (nd > 0 && (ns == 0 || rng.chance(p2))) return validDoubles[rng.nextInt(nd)]
        if (ns > 0) return validSingles[rng.nextInt(ns)]
        return 0
    }

    /** True if from every previously free lane some newly free lane is within [shifts] changes. */
    fun isFair(mask: Int, shifts: Int): Boolean = isFair(prevFreeMask, mask, shifts)

    private fun pickSafeLane(freeMask: Int): Int {
        // Prefer the free lane closest to the previous safe lane; break ties randomly.
        var best = -1
        var bestD = Int.MAX_VALUE
        val offset = rng.nextInt(LANES)
        for (i in 0 until LANES) {
            val l = (i + offset) % LANES
            if (freeMask and (1 shl l) == 0) continue
            val d = abs(l - safeLane)
            if (d < bestD) {
                bestD = d
                best = l
            }
        }
        return if (best < 0) safeLane else best
    }

    companion object {
        fun isFair(prevFree: Int, mask: Int, shifts: Int): Boolean {
            if (mask == 0b111) return false
            val newFree = mask.inv() and 0b111
            for (f in 0 until LANES) {
                if (prevFree and (1 shl f) == 0) continue
                var ok = false
                for (g in 0 until LANES) {
                    if (newFree and (1 shl g) != 0 && abs(f - g) <= shifts) {
                        ok = true
                        break
                    }
                }
                if (!ok) return false
            }
            return true
        }
    }
}
