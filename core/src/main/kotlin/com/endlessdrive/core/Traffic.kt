package com.endlessdrive.core

class Obstacle {
    var kind = 0
    /** 0 = left lane, 1 = right lane, -1 = parked on a shoulder (never blocks a lane). */
    var lane = 0
    var s = 0.0
    var x = 0f
    var speed = 0f
    var desired = 0f
    var halfW = 0f
    var halfL = 0f
    /** Clear distance kept behind this obstacle to the nearest other-lane obstacle (see Difficulty.guard). */
    var guard = 0f
    var alive = false
    var inQueue = false
    var passed = false
    var hazard = false
    var color = 0
    var id = 0
}

interface ClusterListener {
    /** A cluster occupying [lane] from s0 to s1 (metres) was just spawned. */
    fun onCluster(lane: Int, s0: Double, s1: Double, moving: Boolean)
}

/**
 * Traffic pool, spawner and car-following AI.
 *
 * Fairness invariant (checked by tests): for every obstacle A and the first obstacle B ahead of it
 * in the *other* lane, the free distance between them is always >= B.guard. So after passing A
 * there is always room to change lanes before B, at any player speed up to nitro top speed.
 * It holds because
 *  1. clusters are single-lane and are only spawned once that distance exists, and
 *  2. AI vehicles never close in on B beyond its guard (and never overtake), they slow down instead.
 *     Consequence: traffic queues up before a static hazard in either lane, like a real jam before
 *     roadworks, instead of driving alongside it and forming a moving wall.
 * Obstacles are kept in a ring queue ordered by s, so no sorting or allocation is ever needed.
 */
class Traffic(private val capacity: Int = 128) {
    private val pool = Array(capacity) { Obstacle() }
    private var head = 0
    var count = 0
        private set
    private val rng = Rng(1)
    private val lastInLane = arrayOfNulls<Obstacle>(2)
    private var minStart = 0.0
    private var nextId = 1

    // plan for the next cluster
    private var planLane = 0
    private var planGapFactor = 1f
    private var planSameFactor = 0.6f

    var listener: ClusterListener? = null
    var clustersSpawned = 0
        private set
    var spawningEnabled = true

    fun get(i: Int): Obstacle = pool[(head + i) % capacity]

    fun reset(seed: Long, playerS: Double, time: Float = 0f) {
        rng.setSeed(seed)
        for (o in pool) { o.alive = false; o.inQueue = false }
        head = 0; count = 0
        lastInLane[0] = null; lastInLane[1] = null
        minStart = playerS + Cfg.START_CLEAR
        clustersSpawned = 0
        planLane = rng.nextInt(2)
        planGapFactor = 1f
        planSameFactor = 0.6f
        if (spawningEnabled) spawn(playerS, time)
    }

    fun update(dt: Float, playerS: Double, time: Float) {
        // 1. recycle obstacles that are well behind the player
        while (count > 0) {
            val o = pool[head]
            if (o.s + o.halfL >= playerS - Cfg.DESPAWN_BEHIND) break
            o.alive = false; o.inQueue = false
            head = (head + 1) % capacity
            count--
        }
        // 2. AI speeds, front to rear so leaders are already updated
        var i = count - 1
        while (i >= 0) {
            val o = get(i)
            if (o.alive && o.lane >= 0) {
                if (o.desired <= 0f) {
                    o.speed = 0f
                } else {
                    var u = o.desired
                    var same = false
                    var opp = false
                    var j = i + 1
                    while (j < count && !(same && opp)) {
                        val p = get(j)
                        j++
                        if (!p.alive || p.lane < 0) continue
                        val gap = (p.s - p.halfL - (o.s + o.halfL)).toFloat()
                        if (p.lane == o.lane) {
                            if (!same) {
                                same = true
                                val lim = p.speed + Cfg.AI_K * (gap - Cfg.FOLLOW_GAP)
                                if (lim < u) u = lim
                            }
                        } else if (p.lane != o.lane && !opp) {
                            opp = true
                            val lim = p.speed + Cfg.AI_K * (gap - p.guard)
                            if (lim < u) u = lim
                        }
                    }
                    o.speed = if (u < 0f) 0f else u
                }
            }
            i--
        }
        // 3. integrate
        i = 0
        while (i < count) {
            val o = get(i)
            if (o.alive) o.s += o.speed * dt
            i++
        }
        // 4. stream new clusters in beyond the horizon
        if (spawningEnabled) spawn(playerS, time)
    }

    /** Removes everything in [from, to] (used when the player continues after a crash). */
    fun clearRange(from: Double, to: Double) {
        for (i in 0 until count) {
            val o = get(i)
            if (o.s + o.halfL > from && o.s - o.halfL < to) o.alive = false
        }
    }

    fun clearAll() {
        for (i in 0 until count) get(i).alive = false
    }

    // ------------------------------------------------------------------ spawning

    private fun frontEdge(o: Obstacle?): Double =
        if (o == null || !o.inQueue) Double.NEGATIVE_INFINITY else o.s + o.halfL

    private fun spawn(playerS: Double, time: Float) {
        val horizon = playerS + Cfg.SPAWN_AHEAD
        var loops = 0
        while (count < capacity - 24 && loops < 12) {
            loops++
            val cruiseNow = Difficulty.cruise(time)
            // where would the cluster go if nothing constrained it, and when will the player get there?
            var p0 = minStart
            val fo = frontEdge(lastInLane[1 - planLane])
            val fs = frontEdge(lastInLane[planLane])
            if (fo > p0) p0 = fo
            if (fs > p0) p0 = fs
            val tArr = time + ((p0 - playerS) / (0.55 * cruiseNow)).toFloat()
            val level = Difficulty.level(tArr)
            val cruiseArr = Difficulty.cruise(tArr + 5f)

            // static hazards only once the player would meet them well after the grace period
            val isStatic = rng.chance(Difficulty.staticProb(level)) && tArr > Cfg.GRACE_S + 20f
            val speedFactor = rng.range(Cfg.TRAFFIC_MIN_FACTOR, Cfg.TRAFFIC_MAX_FACTOR)
            val base = if (isStatic) 0f else speedFactor * cruiseNow
            val guard = Difficulty.guard(cruiseArr, level, base * 0.95f)

            var need = minStart
            // traffic in the free lane has to stop short of a static hazard (see class doc), so leave extra room
            val gf = if (isStatic) planGapFactor + 1f else planGapFactor
            if (fo + guard * gf > need) need = fo + guard * gf
            val sameGap = maxOf(Cfg.FOLLOW_GAP + 3f, guard * planSameFactor)
            if (fs + sameGap > need) need = fs + sameGap
            if (need > horizon) {
                // Not yet. Keep the plan but undo nothing: the rng draws above only change variety.
                break
            }
            val lane = planLane
            val end = if (isStatic) spawnStatic(lane, need, guard, level) else spawnMoving(lane, need, guard, level, base)
            // occasionally a broken-down car parked on a shoulder (does not block either lane)
            if (level > 0f && rng.chance(0.14f)) {
                val side = if (rng.chance(0.5f)) -1f else 1f
                val k = if (rng.chance(0.7f)) Kind.CAR else Kind.VAN
                val o = add(k, -1, end + 10.0 + Kind.HALF_L[k], side * (Cfg.ROAD_HALF + 0.3f + Kind.HALF_W[k]), 0f, 0f)
                o?.hazard = true
            }
            clustersSpawned++
            listener?.onCluster(lane, need, end, !isStatic)
            minStart = end + 4.0
            // plan the next one
            if (rng.chance(Difficulty.switchProb(level))) planLane = 1 - planLane
            planGapFactor = rng.range(1f, 1.45f)
            planSameFactor = rng.range(0.45f, 0.9f)
        }
    }

    private fun laneX(lane: Int) = if (lane == 0) Cfg.LANE_LEFT_X else Cfg.LANE_RIGHT_X

    private fun add(kind: Int, lane: Int, s: Double, x: Float, desired: Float, guard: Float): Obstacle? {
        if (count >= capacity) return null
        val o = pool[(head + count) % capacity]
        count++
        o.kind = kind; o.lane = lane; o.s = s; o.x = x
        o.desired = desired; o.speed = desired
        o.halfW = Kind.HALF_W[kind]; o.halfL = Kind.HALF_L[kind]
        o.guard = guard
        o.alive = true; o.inQueue = true; o.passed = false; o.hazard = false
        o.color = rng.nextInt(8)
        o.id = nextId++
        if (lane >= 0) lastInLane[lane] = o
        return o
    }

    /** Returns the s of the cluster's front edge. [start] is its rear edge. */
    private fun spawnMoving(lane: Int, start: Double, guard: Float, level: Float, base: Float): Double {
        val n = 1 + rng.nextInt(Difficulty.maxClusterSize(level))
        var cs = start
        for (k in 0 until n) {
            val r = rng.nextFloat()
            val kind = if (level > 0.08f && r > 0.82f) Kind.TRUCK else if (r > 0.6f) Kind.VAN else Kind.CAR
            val c = cs + Kind.HALF_L[kind]
            add(kind, lane, c, laneX(lane) + rng.range(-0.25f, 0.25f), base * rng.range(0.95f, 1.05f), guard)
            cs = c + Kind.HALF_L[kind]
            if (k < n - 1) cs += rng.range(8f, 16f)
        }
        return cs
    }

    private fun spawnStatic(lane: Int, start: Double, guard: Float, level: Float): Double {
        val lx = laneX(lane)
        val r = rng.nextFloat()
        var cs = start
        if (r < 0.32f) {
            // stopped vehicle with hazard lights, two warning cones behind it
            add(Kind.CONE, lane, cs + 0.3, lx - 0.5f, 0f, guard)
            add(Kind.CONE, lane, cs + 4.3, lx + 0.5f, 0f, guard)
            val kind = if (rng.chance(0.3f)) Kind.VAN else Kind.CAR
            val c = cs + 9.0 + Kind.HALF_L[kind]
            add(kind, lane, c, lx + rng.range(-0.2f, 0.2f), 0f, guard)?.hazard = true
            cs = c + Kind.HALF_L[kind]
        } else if (r < 0.56f) {
            // line of cones
            val n = 3 + rng.nextInt(3)
            for (k in 0 until n) {
                add(Kind.CONE, lane, cs + 0.3 + k * 4.5, lx + (if (k % 2 == 0) -0.55f else 0.55f), 0f, guard)
            }
            cs += 0.6 + (n - 1) * 4.5
        } else if (r < 0.76f || level < 0.25f) {
            // single barrier
            add(Kind.CONE, lane, cs + 0.3, lx, 0f, guard)
            add(Kind.BARRIER, lane, cs + 5.0, lx, 0f, guard)
            cs += 5.4
        } else {
            // roadworks: arrow board, cone taper, then barriers closing the lane
            val outer = if (lane == 0) -1f else 1f
            add(Kind.SIGN, lane, cs + 0.5, lx, 0f, guard)
            for (k in 0 until 4) {
                add(Kind.CONE, lane, cs + 5.0 + k * 4.0, lx + outer * (1.3f - k * 0.85f), 0f, guard)
            }
            val len = rng.range(40f, 85f)
            var d = 22.0
            while (d < len) {
                add(Kind.BARRIER, lane, cs + d, lx, 0f, guard)
                add(Kind.CONE, lane, cs + d + 7.0, lx - outer * 1.3f, 0f, guard)
                d += 14.0
            }
            add(Kind.BARRIER, lane, cs + d, lx, 0f, guard)
            cs += d + 0.4
        }
        return cs
    }

    // ------------------------------------------------------------------ test hooks

    fun placeForTest(kind: Int, lane: Int, s: Double, speed: Float = 0f): Obstacle {
        val x = if (lane < 0) Cfg.ROAD_HALF + 1.3f else laneX(lane)
        return add(kind, lane, s, x, speed, 20f)!!
    }
}
