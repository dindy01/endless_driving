package com.endlessdrive.engine

import com.endlessdrive.core.Cfg
import com.endlessdrive.core.Rng
import com.endlessdrive.core.Road
import com.endlessdrive.core.RoadFrame
import kotlin.math.cos
import kotlin.math.sin

/** One streamed, recycled piece of world: road surface, markings, rails, terrain and scenery in a single mesh. */
class Chunk {
    var index = Int.MIN_VALUE
    var generation = -1
    var vbo = 0
    var vertexCount = 0
    var originX = 0.0
    var originZ = 0.0
    /** Street-lamp heads (chunk-local xyz) so the renderer can add glows at night. */
    val lamps = FloatArray(MAX_LAMPS * 3)
    var lampCount = 0

    companion object {
        const val MAX_LAMPS = 4
    }
}

/**
 * Builds chunk geometry. Everything is a deterministic function of (road seed, chunk index), so a
 * chunk can be rebuilt identically at any time (e.g. after the GL context is lost).
 */
class ChunkMesher {
    val builder = MeshBuilder(14000)
    private val f = RoadFrame()
    private val rng = Rng(1)
    private val n = SAMPLES + 1
    // per-sample centreline (chunk-local) and right vector
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val pz = FloatArray(n)
    private val rx = FloatArray(n)
    private val rz = FloatArray(n)
    private var ox = 0.0
    private var oz = 0.0

    fun build(road: Road, seed: Long, chunk: Chunk, index: Int, density: Float) {
        val b = builder
        b.reset()
        val s0 = index * LENGTH.toDouble()
        road.frame(s0, f)
        ox = f.x; oz = f.z
        chunk.originX = ox; chunk.originZ = oz
        chunk.index = index
        chunk.lampCount = 0
        for (j in 0 until n) {
            road.frame(s0 + j * Road.STEP, f)
            px[j] = (f.x - ox).toFloat(); py[j] = f.y; pz[j] = (f.z - oz).toFloat()
            rx[j] = cos(f.heading); rz[j] = sin(f.heading)
        }
        val biome = biomeOf(seed, index)

        // ---- road surface: non-overlapping strips so nothing z-fights
        val asphalt = 0x3b3d42
        val asphalt2 = 0x37393e
        val shoulder = if (biome == TOWN) 0x77736b else 0x857a66
        for (j in 0 until SAMPLES) {
            val gi = index * SAMPLES + j
            strip(j, -Cfg.RAIL_X - 0.15f, -Cfg.ROAD_HALF, shoulder)
            strip(j, -Cfg.ROAD_HALF, -Cfg.ROAD_HALF + 0.16f, 0xe9e9e4)
            strip(j, -Cfg.ROAD_HALF + 0.16f, -1.75f, if (gi % 2 == 0) asphalt else asphalt2)
            strip(j, -1.75f, -0.08f, if (gi % 2 == 0) asphalt2 else asphalt)
            strip(j, -0.08f, 0.08f, if (gi % 5 < 2) 0xe9e9e4 else asphalt)
            strip(j, 0.08f, 1.75f, if (gi % 2 == 0) asphalt else asphalt2)
            strip(j, 1.75f, Cfg.ROAD_HALF - 0.16f, if (gi % 2 == 0) asphalt2 else asphalt)
            strip(j, Cfg.ROAD_HALF - 0.16f, Cfg.ROAD_HALF, 0xe9e9e4)
            strip(j, Cfg.ROAD_HALF, Cfg.RAIL_X + 0.15f, shoulder)
        }

        // ---- guard rails
        val railX = Cfg.RAIL_X + 0.08f
        for (side in intArrayOf(-1, 1)) {
            for (j in 0 until SAMPLES) {
                val ax = px[j] + rx[j] * railX * side; val az = pz[j] + rz[j] * railX * side
                val bx = px[j + 1] + rx[j + 1] * railX * side; val bz = pz[j + 1] + rz[j + 1] * railX * side
                b.color(0xb4b9c0)
                b.quad(ax, py[j] + 0.42f, az, bx, py[j + 1] + 0.42f, bz, bx, py[j + 1] + 0.74f, bz, ax, py[j] + 0.74f, az, -rx[j] * side, 0.15f, -rz[j] * side)
                b.color(0x8d9299)
                b.quad(ax, py[j] + 0.74f, az, bx, py[j + 1] + 0.74f, bz,
                    bx + rx[j + 1] * 0.1f * side, py[j + 1] + 0.74f, bz + rz[j + 1] * 0.1f * side,
                    ax + rx[j] * 0.1f * side, py[j] + 0.74f, az + rz[j] * 0.1f * side, 0f, 1f, 0f)
                if (j % 2 == 0) {
                    b.color(0x6d7178)
                    b.place(ax + rx[j] * 0.06f * side, py[j], az + rz[j] * 0.06f * side)
                    b.box(0f, 0.36f, 0f, 0.05f, 0.36f, 0.05f)
                    b.identity()
                }
            }
        }

        // ---- terrain
        val g0 = grass(biome, 0)
        val g1 = grass(biome, 1)
        for (side in intArrayOf(-1, 1)) {
            for (j in 0 until SAMPLES) {
                val gi = index * SAMPLES + j
                for (c in 0 until COLS.size - 1) {
                    val xa = COLS[c] * side; val xb = COLS[c + 1] * side
                    val col = if (c >= 4) g0 else if (((gi + c) * 7 + (if (side > 0) 3 else 0)) % 3 == 0) g1 else g0
                    b.color(if (c == 0) shoulderDirt(biome) else col)
                    val a0x = px[j] + rx[j] * xa; val a0z = pz[j] + rz[j] * xa
                    val b0x = px[j] + rx[j] * xb; val b0z = pz[j] + rz[j] * xb
                    val a1x = px[j + 1] + rx[j + 1] * xa; val a1z = pz[j + 1] + rz[j + 1] * xa
                    val b1x = px[j + 1] + rx[j + 1] * xb; val b1z = pz[j + 1] + rz[j + 1] * xb
                    b.quad(
                        a0x, ground(py[j], a0x, a0z, COLS[c]), a0z, b0x, ground(py[j], b0x, b0z, COLS[c + 1]), b0z,
                        b1x, ground(py[j + 1], b1x, b1z, COLS[c + 1]), b1z, a1x, ground(py[j + 1], a1x, a1z, COLS[c]), a1z,
                        0f, 1f, 0f,
                    )
                }
            }
        }

        // ---- scenery (deterministic per chunk)
        rng.setSeed(seed * 1000003L + index * 7919L + 17L)
        scenery(chunk, index, biome, density)

        chunk.vertexCount = b.vertexCount
    }

    private fun strip(j: Int, xa: Float, xb: Float, rgb: Int) {
        val b = builder
        b.color(rgb)
        b.quad(
            px[j] + rx[j] * xa, py[j], pz[j] + rz[j] * xa, px[j] + rx[j] * xb, py[j], pz[j] + rz[j] * xb,
            px[j + 1] + rx[j + 1] * xb, py[j + 1], pz[j + 1] + rz[j + 1] * xb, px[j + 1] + rx[j + 1] * xa, py[j + 1], pz[j + 1] + rz[j + 1] * xa,
            0f, 1f, 0f,
        )
    }

    /**
     * Terrain height at chunk-local (lx, lz), lateral distance [lat] from the road whose height there is [roadY].
     * Near the road it follows the road; far away it becomes a world-space plain that is always
     * below the lowest road, so terrain from one chunk can never bury another chunk's road.
     */
    private fun ground(roadY: Float, lx: Float, lz: Float, lat: Float): Float {
        val wx = (lx + ox).toFloat(); val wz = (lz + oz).toFloat()
        val plain = PLAIN_Y + 1.3f * sin(wx * 0.021f + 1.3f) * cos(wz * 0.017f) + 0.5f * sin(wx * 0.063f + wz * 0.051f)
        if (lat <= Cfg.RAIL_X + 0.2f) return roadY - 0.02f
        val near = roadY - 0.25f - (lat - Cfg.RAIL_X) * 0.12f + 0.35f * sin(wx * 0.19f) * sin(wz * 0.23f)
        var t = (lat - 9f) / 45f
        if (t < 0f) t = 0f
        if (t > 1f) t = 1f
        t = t * t * (3f - 2f * t)
        val y = near + (plain - near) * t
        return if (lat > 200f) y - 3f else y
    }

    private fun groundAt(j: Int, lat: Float, side: Int): Float {
        val x = px[j] + rx[j] * lat * side; val z = pz[j] + rz[j] * lat * side
        return ground(py[j], x, z, lat)
    }

    private fun scenery(chunk: Chunk, index: Int, biome: Int, density: Float) {
        val b = builder
        // street lamps: alternate sides every other chunk in towns, sparser elsewhere
        val lampEvery = if (biome == TOWN) 1 else 3
        if (index % lampEvery == 0) {
            val side = if ((index / lampEvery) % 2 == 0) 1 else -1
            val j = 4
            val lat = Cfg.RAIL_X + 0.7f
            val x = px[j] + rx[j] * lat * side; val z = pz[j] + rz[j] * lat * side
            val yaw = -heading(j) + (if (side > 0) 3.1415927f else 0f)
            b.place(x, py[j] - 0.3f, z, yaw)
            b.color(0x565b63)
            b.box(0f, 3.6f, 0f, 0.09f, 3.6f, 0.09f)
            b.box(1.1f, 7.1f, 0f, 1.2f, 0.07f, 0.07f)
            b.color(0xffefb0, 3)
            b.box(2.0f, 7.0f, 0f, 0.35f, 0.07f, 0.16f)
            b.identity()
            if (chunk.lampCount < Chunk.MAX_LAMPS) {
                // lamp head position: local +x of the lamp maps towards the road
                val hx = x - rx[j] * 2.0f * side; val hz = z - rz[j] * 2.0f * side
                val o = chunk.lampCount * 3
                chunk.lamps[o] = hx; chunk.lamps[o + 1] = py[j] + 6.65f; chunk.lamps[o + 2] = hz
                chunk.lampCount++
            }
        }
        // roadside sign now and then
        if (rng.chance(0.3f)) {
            val side = if (rng.chance(0.5f)) 1 else -1
            val j = 9
            val lat = Cfg.RAIL_X + 1.1f
            b.place(px[j] + rx[j] * lat * side, py[j] - 0.3f, pz[j] + rz[j] * lat * side, -heading(j))
            b.color(0x6a6f77)
            b.box(0f, 1.4f, 0f, 0.05f, 1.4f, 0.05f)
            if (rng.chance(0.5f)) {
                b.color(0xd8261c); b.disc(0f, 2.9f, 0.06f, 0.5f, 0.02f, 10)
                b.color(0xf7f7f2); b.disc(0f, 2.9f, 0.09f, 0.37f, 0.01f, 10)
            } else {
                b.color(0x1f5fbf); b.box(0f, 2.9f, 0.06f, 0.6f, 0.42f, 0.02f)
                b.color(0xf7f7f2); b.box(0f, 2.9f, 0.09f, 0.45f, 0.06f, 0.01f)
            }
            b.identity()
        }
        // billboard in open country
        if (biome == FIELDS && rng.chance(0.22f)) {
            val side = if (rng.chance(0.5f)) 1 else -1
            val j = 6
            val lat = 15f
            b.place(px[j] + rx[j] * lat * side, groundAt(j, lat, side) - 0.3f, pz[j] + rz[j] * lat * side, -heading(j) + side * 0.35f)
            b.color(0x5b5148)
            b.box(-2.2f, 2.2f, 0f, 0.12f, 2.2f, 0.12f); b.box(2.2f, 2.2f, 0f, 0.12f, 2.2f, 0.12f)
            b.color(0xf2efe6); b.box(0f, 5.3f, 0f, 3.4f, 1.5f, 0.1f)
            val c = BILLBOARD[rng.nextInt(BILLBOARD.size)]
            b.color(c); b.box(0f, 5.3f, 0.12f, 3.1f, 1.25f, 0.02f)
            b.color(0xfff6d8); b.box(-0.9f, 5.5f, 0.15f, 1.6f, 0.22f, 0.01f); b.box(-1.5f, 4.9f, 0.15f, 1.0f, 0.12f, 0.01f)
            b.disc(2.0f, 5.3f, 0.15f, 0.7f, 0.01f, 8)
            b.identity()
        }

        val trees = ((if (biome == FOREST) 22 else if (biome == FIELDS) 5 else 7) * density + 0.5f).toInt()
        for (k in 0 until trees) {
            val side = if (rng.chance(0.5f)) 1 else -1
            val j = rng.nextInt(SAMPLES)
            val lat = if (biome == FOREST) rng.range(9.5f, 46f) else rng.range(10f, 44f)
            val x = px[j] + rx[j] * lat * side; val z = pz[j] + rz[j] * lat * side
            val y = groundAt(j, lat, side) - 0.35f
            val sc = rng.range(0.8f, 1.5f)
            b.place(x, y, z, rng.range(0f, 6.28f), sc, sc * rng.range(0.9f, 1.25f))
            if (biome == FOREST && rng.chance(0.75f) || rng.chance(0.3f)) pine() else roundTree()
            b.identity()
        }
        // bushes and rocks close to the road for near-field parallax
        val small = (7 * density + 0.5f).toInt()
        for (k in 0 until small) {
            val side = if (rng.chance(0.5f)) 1 else -1
            val j = rng.nextInt(SAMPLES)
            val lat = rng.range(7.3f, 13f)
            val x = px[j] + rx[j] * lat * side; val z = pz[j] + rz[j] * lat * side
            b.place(x, groundAt(j, lat, side) - 0.1f, z, rng.range(0f, 6.28f), rng.range(0.6f, 1.3f))
            if (rng.chance(0.4f)) {
                b.color(0x7d7f84); b.cone(0f, 0f, 0f, 0.6f, 0.5f, 0.3f, 5); b.color(0x8d8f94); b.cone(0f, 0.5f, 0f, 0.3f, 0.62f, 0f, 5)
            } else {
                b.color(if (biome == FIELDS) 0x8f9a3a else 0x3f7a35); b.cone(0f, 0f, 0f, 0.75f, 0.45f, 0.6f, 6); b.cone(0f, 0.45f, 0f, 0.6f, 0.95f, 0f, 6)
            }
            b.identity()
        }
        if (biome == FIELDS) {
            val bales = (3 * density + 0.5f).toInt()
            for (k in 0 until bales) {
                val side = if (rng.chance(0.5f)) 1 else -1
                val j = rng.nextInt(SAMPLES)
                val lat = rng.range(14f, 42f)
                b.place(px[j] + rx[j] * lat * side, groundAt(j, lat, side) - 0.1f, pz[j] + rz[j] * lat * side, rng.range(0f, 6.28f))
                b.color(0xd9b44a); b.cone(0f, 0f, 0f, 1.0f, 1.3f, 1.0f, 8); b.color(0xe6c860); b.cone(0f, 1.3f, 0f, 1.0f, 1.32f, 0f, 8)
                b.identity()
            }
        }
        if (biome == TOWN) {
            val count = (3 * density + 1.2f).toInt()
            for (k in 0 until count) {
                val side = if (k % 2 == 0) 1 else -1
                val j = 2 + ((k / 2) * 7 + rng.nextInt(3)) % (SAMPLES - 3)
                val w = rng.range(4.5f, 7f); val d = rng.range(4.5f, 6.5f)
                val h = rng.range(6f, 19f)
                val lat = rng.range(17f, 31f) + d
                val x = px[j] + rx[j] * lat * side; val z = pz[j] + rz[j] * lat * side
                val base = groundAt(j, lat, side) - 1.5f
                // local +z faces the road
                b.place(x, base, z, -heading(j) + (if (side > 0) -1.5707964f else 1.5707964f))
                building(w, d, h + 1.5f)
                b.identity()
            }
        }
    }

    private fun heading(j: Int): Float = kotlin.math.atan2(rz[j], rx[j])

    private fun pine() {
        val b = builder
        b.color(0x5a4028); b.cone(0f, 0f, 0f, 0.22f, 1.6f, 0.16f, 5)
        b.color(0x2e6b3a); b.cone(0f, 1.2f, 0f, 1.7f, 3.3f, 0.5f, 6)
        b.color(0x357a40); b.cone(0f, 2.8f, 0f, 1.3f, 4.8f, 0.3f, 6, 0.5f)
        b.color(0x3f8a48); b.cone(0f, 4.3f, 0f, 0.9f, 6.3f, 0f, 6)
    }

    private fun roundTree() {
        val b = builder
        b.color(0x644a30); b.cone(0f, 0f, 0f, 0.26f, 2.2f, 0.18f, 5)
        b.color(0x4c9440); b.cone(0f, 1.8f, 0f, 0.9f, 3.2f, 1.9f, 6)
        b.color(0x5aa64a); b.cone(0f, 3.2f, 0f, 1.9f, 4.6f, 1.3f, 6, 0.4f)
        b.color(0x6ab555); b.cone(0f, 4.6f, 0f, 1.3f, 5.4f, 0f, 6)
    }

    private fun building(w: Float, d: Float, h: Float) {
        val b = builder
        val wall = WALLS[rng.nextInt(WALLS.size)]
        b.color(wall)
        b.box(0f, h * 0.5f, 0f, w, h * 0.5f, d)
        b.color(0x4a4d55)
        b.box(0f, h + 0.15f, 0f, w + 0.15f, 0.15f, d + 0.15f)
        if (rng.chance(0.5f)) b.box(w * 0.3f, h + 0.9f, 0f, 1.0f, 0.6f, 1.0f)
        // windows on the face towards the road (+z) and on both ends
        val floors = ((h - 2f) / 3f).toInt()
        val cols = (w / 1.6f).toInt()
        for (fl in 0 until floors) {
            val y = 2.6f + fl * 3f
            for (c in 0 until cols) {
                val x = -w + (c + 0.5f) * (2f * w / cols)
                if (rng.chance(0.55f)) b.color(0xffd98a, 3) else b.color(0x232a36)
                b.box(x, y, d + 0.03f, 0.45f, 0.65f, 0.02f)
            }
            for (e in intArrayOf(-1, 1)) {
                if (rng.chance(0.5f)) b.color(0xffe2a0, 3) else b.color(0x232a36)
                b.box(e * (w + 0.03f), y, 0f, 0.02f, 0.65f, d * 0.5f)
            }
        }
    }

    companion object {
        const val SAMPLES = 16
        const val LENGTH = SAMPLES * 2f
        const val PLAIN_Y = -7f
        const val FOREST = 0
        const val FIELDS = 1
        const val TOWN = 2
        private val COLS = floatArrayOf(Cfg.RAIL_X + 0.15f, 9f, 15f, 26f, 48f, 110f, 260f, 520f)
        private val WALLS = intArrayOf(0xd8c9b0, 0xc9b8a8, 0xb9c4cc, 0xd9d2c5, 0xc7a591, 0xa9b3a2, 0xe0d6c2)
        private val BILLBOARD = intArrayOf(0xe2452f, 0x2f7de2, 0x2fae6b, 0xf2a71b, 0x8a4fd1)

        fun biomeOf(seed: Long, index: Int): Int {
            val block = Math.floorDiv(index, 9)
            var h = seed * 31L + block * 0x9E3779B97F4A7C15uL.toLong()
            h = h xor (h ushr 29)
            h *= -0x40a7b892e31b1a47L
            h = h xor (h ushr 32)
            val r = ((h ushr 8) % 10).toInt().let { if (it < 0) -it else it }
            return if (r < 4) FOREST else if (r < 7) FIELDS else TOWN
        }

        fun grass(biome: Int, variant: Int): Int = when (biome) {
            FOREST -> if (variant == 0) 0x4b8a3d else 0x447f38
            FIELDS -> if (variant == 0) 0x8fa043 else 0xa3ad4c
            else -> if (variant == 0) 0x5f9148 else 0x6b9a52
        }

        fun shoulderDirt(biome: Int): Int = if (biome == FIELDS) 0x8c8355 else 0x5d7a45
    }
}
