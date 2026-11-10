package com.endlessdrive.render

import kotlin.math.floor
import kotlin.math.sin

/**
 * Streams the road as fixed-length chunks. Each pooled chunk owns one dynamic VBO holding the
 * asphalt, markings, shoulders, guard rails, terrain verges AND baked scenery (trees, buildings,
 * signs, lamps) in chunk-local coordinates. Chunks are rebuilt in place when recycled: constant
 * memory, ~1 draw call per chunk, no visible pop-in (chunks are built beyond the fog distance).
 */
class RoadChunks(private val meshes: Meshes, private val slots: Int = 18) {
    private val mesh = Array(slots) { Mesh(CAPACITY_FLOATS) }
    private val chunkId = LongArray(slots) { -1L }
    val originX = DoubleArray(slots)
    val originY = DoubleArray(slots)
    val originZ = DoubleArray(slots)
    /** Lamp head positions (chunk-local) for night glows. */
    val lampCount = IntArray(slots)
    val lamps = Array(slots) { FloatArray(MAX_LAMPS * 3) }
    private val b = MeshBuilder(CAPACITY_FLOATS)
    private val pose = RoadPose()
    private val pose2 = RoadPose()
    private val m = FloatArray(16)
    private var density = 1f
    var rebuilds = 0; private set

    fun init(gl: GL) {
        for (i in 0 until slots) {
            mesh[i].createDynamic(gl)
            chunkId[i] = -1L
        }
    }

    fun invalidate() {
        for (i in 0 until slots) chunkId[i] = -1L
    }

    fun setDensity(d: Float) {
        if (d != density) {
            density = d
            invalidate()
        }
    }

    fun slotCount() = slots
    fun slotChunk(i: Int) = chunkId[i]
    fun slotMesh(i: Int) = mesh[i]

    /** Ensures chunks covering [sMin, sMax] are built. */
    fun update(gl: GL, road: RoadGeometry, sMin: Double, sMax: Double) {
        val first = floor(sMin / LEN).toLong()
        val last = floor(sMax / LEN).toLong()
        // free slots that are out of range
        for (i in 0 until slots) if (chunkId[i] != -1L && (chunkId[i] < first || chunkId[i] > last)) chunkId[i] = -1L
        var id = first
        while (id <= last) {
            if (!has(id)) {
                val slot = freeSlot()
                if (slot < 0) break
                build(gl, road, slot, id)
            }
            id++
        }
    }

    private fun has(id: Long): Boolean {
        for (i in 0 until slots) if (chunkId[i] == id) return true
        return false
    }

    private fun freeSlot(): Int {
        for (i in 0 until slots) if (chunkId[i] == -1L) return i
        return -1
    }

    // Lateral cross-section (metres from centreline) and strip colours.
    private val lat = floatArrayOf(-70f, -24f, -12f, -7.6f, -6.6f, -6.1f, -3.5f, 0f, 3.5f, 6.1f, 6.6f, 7.6f, 12f, 24f, 70f)
    private val stripColor = intArrayOf(
        0x4F7A35, 0x5B8A3C, 0x6A9A44, 0x8A8170, 0x7A7468, 0x575B62, 0x3B3E45,
        0x3B3E45, 0x575B62, 0x7A7468, 0x8A8170, 0x6A9A44, 0x5B8A3C, 0x4F7A35,
    )

    fun terrainY(s: Double, l: Float): Float {
        val a = kotlin.math.abs(l)
        if (a <= 6.6f) return 0f
        if (a <= 7.6f) return -0.25f * (a - 6.6f)
        if (a <= 12f) return -0.25f - 0.35f * (a - 7.6f) / 4.4f
        val side = if (l < 0f) 1.7 else 4.1
        val hill = (6.0 * sin(s / 230.0 + side) + 3.0 * sin(s / 97.0 + side * 2.3)).toFloat()
        val t = ((a - 12f) / 58f).coerceIn(0f, 1f)
        return -0.6f + (hill + 2.5f) * t
    }

    private fun build(gl: GL, road: RoadGeometry, slot: Int, id: Long) {
        val s0 = id * LEN
        road.pose(s0, pose)
        originX[slot] = pose.x; originY[slot] = pose.y; originZ[slot] = pose.z
        b.reset()
        val n = (LEN / RoadGeometry.STEP).toInt()
        for (k in 0 until n) {
            val sa = s0 + k * RoadGeometry.STEP
            val sb = sa + RoadGeometry.STEP
            road.pose(sa, pose)
            road.pose(sb, pose2)
            val ax = (pose.x - originX[slot]).toFloat(); val ay = (pose.y - originY[slot]).toFloat(); val az = (pose.z - originZ[slot]).toFloat()
            val bx = (pose2.x - originX[slot]).toFloat(); val by = (pose2.y - originY[slot]).toFloat(); val bz = (pose2.z - originZ[slot]).toFloat()
            val arx = pose.rx; val arz = pose.rz; val brx = pose2.rx; val brz = pose2.rz
            val gIdx = Math.floorDiv(sa.toLong(), 2L)
            for (j in 0 until lat.size - 1) {
                val l0 = lat[j]; val l1 = lat[j + 1]
                var col = stripColor[j]
                if (j == 6 || j == 7) col = if ((gIdx * 7 + j) % 9 == 0L) 0x36393F else col
                if (j == 0 || j == 13) col = if (((gIdx / 3) + j) % 4 == 0L) 0x56813A else col
                b.color(col)
                val y0a = terrainY(sa, l0); val y1a = terrainY(sa, l1)
                val y0b = terrainY(sb, l0); val y1b = terrainY(sb, l1)
                b.quad(
                    ax + arx * l0, ay + y0a, az + arz * l0,
                    ax + arx * l1, ay + y1a, az + arz * l1,
                    bx + brx * l1, by + y1b, bz + brz * l1,
                    bx + brx * l0, by + y0b, bz + brz * l0,
                )
            }
            // markings (slightly above the asphalt)
            b.color(0xF2F2F2, 0.15f)
            stripe(ax, ay, az, arx, arz, bx, by, bz, brx, brz, -3.38f, 0.14f)
            stripe(ax, ay, az, arx, arz, bx, by, bz, brx, brz, 3.38f, 0.14f)
            if (Math.floorMod(gIdx, 5L) < 2) stripe(ax, ay, az, arx, arz, bx, by, bz, brx, brz, 0f, 0.13f)
            // guard rails
            for (side in 0..1) {
                val l = if (side == 0) -6.45f else 6.45f
                b.color(0xA7B0B8)
                val o = if (side == 0) -8f else 8f
                b.quad(
                    ax + arx * l, ay + 0.45f, az + arz * l, bx + brx * l, by + 0.45f, bz + brz * l,
                    bx + brx * l, by + 0.8f, bz + brz * l, ax + arx * l, ay + 0.8f, az + arz * l,
                    ax + arx * o, ay + 0.6f, az + arz * o,
                )
                if (Math.floorMod(gIdx, 2L) == 0L) {
                    b.color(0x6F767D)
                    val lp = l + (if (side == 0) -0.12f else 0.12f)
                    b.box(ax + arx * lp, ay + 0.42f, az + arz * lp, 0.06f, 0.42f, 0.06f)
                }
            }
        }
        lampCount[slot] = 0
        bakeScenery(road, slot, id, s0)
        mesh[slot].update(gl, b)
        chunkId[slot] = id
        rebuilds++
    }

    private fun stripe(
        ax: Float, ay: Float, az: Float, arx: Float, arz: Float,
        bx: Float, by: Float, bz: Float, brx: Float, brz: Float, l: Float, w: Float,
    ) {
        val h = 0.015f
        b.quad(
            ax + arx * (l - w / 2), ay + h, az + arz * (l - w / 2), ax + arx * (l + w / 2), ay + h, az + arz * (l + w / 2),
            bx + brx * (l + w / 2), by + h, bz + brz * (l + w / 2), bx + brx * (l - w / 2), by + h, bz + brz * (l - w / 2),
        )
    }

    private fun hash(a: Long, salt: Int): Int {
        var x = (a * 0x9E3779B97F4A7C15uL.toLong() + salt * 0x632BE59BD9B4E019uL.toLong())
        x = (x xor (x ushr 31)) * 0x94D049BB133111EBuL.toLong()
        x = x xor (x ushr 29)
        return (x and 0x7fffffff).toInt()
    }

    private fun frac(h: Int, sh: Int) = ((h ushr sh) and 1023) / 1023f

    /** Places scenery deterministically from the chunk id; "towns" come and go along the road. */
    private fun bakeScenery(road: RoadGeometry, slot: Int, id: Long, s0: Double) {
        val town = hash(id / 14, 77) % 3 == 0
        val items = ((if (town) 7 else 9) * density).toInt().coerceAtLeast(1)
        for (k in 0 until items) {
            val h = hash(id, 1000 + k)
            val side = if (h and 1 == 0) -1f else 1f
            val s = s0 + frac(h, 2) * LEN
            road.pose(s, pose)
            val px = (pose.x - originX[slot]).toFloat()
            val py = (pose.y - originY[slot]).toFloat()
            val pz = (pose.z - originZ[slot]).toFloat()
            val kind = (h ushr 13) % 10
            val l: Float
            val src: MeshBuilder
            var sx = 1f; var sy = 1f; var sz = 1f
            if (town && kind < 5) {
                src = meshes.buildings[(h ushr 17) % 3]
                sx = 8f + frac(h, 19) * 8f; sy = 6f + frac(h, 21) * 20f; sz = 8f + frac(h, 11) * 6f
                // keep the facade at least 22 m from the centreline so it never crowds the view
                l = side * (22f + maxOf(sx, sz) * 0.75f + frac(h, 7) * 20f)
            } else if (kind < 5) {
                l = side * (10f + frac(h, 7) * 45f)
                src = meshes.pine
                val sc = 0.8f + frac(h, 19) * 0.6f; sx = sc; sy = sc * (0.9f + frac(h, 21) * 0.4f); sz = sc
            } else if (kind < 8) {
                l = side * (11f + frac(h, 7) * 40f)
                src = if (kind == 7) meshes.bush else meshes.broadleaf
                val sc = 0.8f + frac(h, 19) * 0.6f; sx = sc; sy = sc; sz = sc
            } else if (kind == 8 && k == 0) {
                l = side * 9.5f
                src = meshes.billboard
            } else {
                l = side * (14f + frac(h, 7) * 30f)
                src = meshes.bush
            }
            val y = terrainY(s, l)
            val wx = px + pose.rx * l; val wz = pz + pose.rz * l
            // face the road (or random yaw for nature)
            val yaw = if (src === meshes.billboard) pose.heading + 3.1416f else frac(h, 23) * 6.28f
            Mat4.model(m, kotlin.math.sin(yaw), 0f, kotlin.math.cos(yaw), wx, py + y, wz, sx, sy, sz)
            b.append(src, m)
        }
        // Street lamps in towns (both sides, every 24 m) with night glow positions.
        if (town && density > 0.5f) {
            for (side in 0..1) {
                val sgn = if (side == 0) -1f else 1f
                val s = s0 + (if (side == 0) 4.0 else 16.0)
                road.pose(s, pose)
                val l = sgn * 7.3f
                val px = (pose.x - originX[slot]).toFloat() + pose.rx * l
                val py = (pose.y - originY[slot]).toFloat()
                val pz = (pose.z - originZ[slot]).toFloat() + pose.rz * l
                // arm (+X local) must point at the road: X = up × forward, so choose forward accordingly
                val fx = pose.fx * sgn; val fz = pose.fz * sgn
                Mat4.model(m, fx, 0f, fz, px, py, pz)
                b.append(meshes.lamp, m)
                val c = lampCount[slot]
                if (c < MAX_LAMPS) {
                    val arr = lamps[slot]
                    // head at local (1.9, 7.0, 0) -> world: X axis = (fz, 0, -fx)
                    arr[c * 3] = px + fz * 1.9f
                    arr[c * 3 + 1] = py + 7.0f
                    arr[c * 3 + 2] = pz - fx * 1.9f
                    lampCount[slot] = c + 1
                }
            }
        }
    }

    companion object {
        const val LEN = 24.0
        const val CAPACITY_FLOATS = 9000 * MeshBuilder.FLOATS
        const val MAX_LAMPS = 4
    }
}
