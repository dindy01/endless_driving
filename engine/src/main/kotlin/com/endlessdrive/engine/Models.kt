package com.endlessdrive.engine

/**
 * Procedural low-poly models (no asset files). All models live in one vertex buffer;
 * [first]/[count] index into it. Model space: +y up, -z forward, origin on the ground.
 */
class Models {
    val builder = MeshBuilder(8192)
    val first = IntArray(COUNT)
    val count = IntArray(COUNT)

    init {
        val b = builder
        begin(PLAYER); player(b); end(PLAYER)
        begin(CAR); sedan(b); end(CAR)
        begin(VAN); van(b); end(VAN)
        begin(TRUCK); truck(b); end(TRUCK)
        begin(CONE); cone(b); end(CONE)
        begin(BARRIER); barrier(b); end(BARRIER)
        begin(SIGN); arrowBoard(b); end(SIGN)
        begin(COIN); coin(b); end(COIN)
        begin(SHIELD); shieldPickup(b); end(SHIELD)
        begin(BUBBLE); bubble(b); end(BUBBLE)
    }

    private fun begin(id: Int) { first[id] = builder.vertexCount; builder.identity() }
    private fun end(id: Int) { count[id] = builder.vertexCount - first[id] }

    private fun wheels(b: MeshBuilder, x: Float, zFront: Float, zRear: Float, r: Float = 0.34f, w: Float = 0.13f) {
        b.color(0x15161a)
        for (sx in intArrayOf(-1, 1)) {
            b.frustum(w, 0f, zFront - r * 0.7f, zFront + r * 0.7f, w, r * 2f, zFront - r * 0.7f, zFront + r * 0.7f, sx * x)
            b.frustum(w, 0f, zRear - r * 0.7f, zRear + r * 0.7f, w, r * 2f, zRear - r * 0.7f, zRear + r * 0.7f, sx * x)
            b.frustum(w, r * 0.3f, zFront - r, zFront + r, w, r * 1.7f, zFront - r, zFront + r, sx * x)
            b.frustum(w, r * 0.3f, zRear - r, zRear + r, w, r * 1.7f, zRear - r, zRear + r, sx * x)
        }
        b.color(0x9aa0a8)
        for (sx in intArrayOf(-1, 1)) {
            b.box(sx * (x + w), r, zFront, 0.01f, r * 0.45f, r * 0.45f)
            b.box(sx * (x + w), r, zRear, 0.01f, r * 0.45f, r * 0.45f)
        }
    }

    private fun lights(b: MeshBuilder, zFront: Float, zRear: Float, y: Float, x: Float, w: Float = 0.2f) {
        b.color(0xfff3c4, 1)
        b.box(-x, y, zFront, w, 0.07f, 0.03f); b.box(x, y, zFront, w, 0.07f, 0.03f)
        b.color(0xff2416, 1)
        b.box(-x, y + 0.04f, zRear, w, 0.07f, 0.03f); b.box(x, y + 0.04f, zRear, w, 0.07f, 0.03f)
    }

    private fun sedan(b: MeshBuilder) {
        wheels(b, 0.84f, -1.3f, 1.3f)
        b.color(0xffffff, 2)
        b.frustum(0.93f, 0.26f, -2.06f, 2.06f, 0.9f, 0.55f, -2.1f, 2.1f)
        b.frustum(0.9f, 0.55f, -2.1f, 2.1f, 0.86f, 0.82f, -1.95f, 2.0f)
        b.color(0x1b2732)
        b.frustum(0.84f, 0.82f, -0.75f, 1.55f, 0.68f, 1.32f, -0.2f, 1.1f)
        b.color(0xffffff, 2)
        b.box(0f, 1.34f, 0.45f, 0.68f, 0.025f, 0.66f)
        b.color(0x22252b)
        b.box(0f, 0.36f, -2.1f, 0.9f, 0.09f, 0.04f); b.box(0f, 0.36f, 2.1f, 0.9f, 0.09f, 0.04f)
        lights(b, -2.12f, 2.12f, 0.64f, 0.62f)
    }

    private fun player(b: MeshBuilder) {
        wheels(b, 0.86f, -1.32f, 1.28f, 0.35f, 0.15f)
        b.color(0xffffff, 2)
        b.frustum(0.94f, 0.22f, -2.05f, 2.05f, 0.92f, 0.5f, -2.1f, 2.1f)
        b.frustum(0.92f, 0.5f, -2.1f, 2.1f, 0.84f, 0.74f, -1.7f, 2.05f)
        b.color(0x10161d)
        b.frustum(0.8f, 0.74f, -0.55f, 1.5f, 0.62f, 1.14f, 0.05f, 1.05f)
        b.color(0xffffff, 2)
        b.box(0f, 1.155f, 0.55f, 0.62f, 0.02f, 0.5f)
        // racing stripes
        b.color(0xf4f4f0)
        b.box(-0.16f, 0.752f, -1.2f, 0.09f, 0.012f, 0.62f); b.box(0.16f, 0.752f, -1.2f, 0.09f, 0.012f, 0.62f)
        b.box(-0.16f, 1.18f, 0.55f, 0.09f, 0.01f, 0.5f); b.box(0.16f, 1.18f, 0.55f, 0.09f, 0.01f, 0.5f)
        // spoiler
        b.color(0x16181c)
        b.box(-0.6f, 0.86f, 1.9f, 0.04f, 0.13f, 0.08f); b.box(0.6f, 0.86f, 1.9f, 0.04f, 0.13f, 0.08f)
        b.box(0f, 1.0f, 1.92f, 0.9f, 0.03f, 0.16f)
        // diffuser + exhausts
        b.box(0f, 0.3f, 2.1f, 0.9f, 0.1f, 0.04f)
        b.color(0x8a8f96)
        b.box(-0.45f, 0.3f, 2.14f, 0.08f, 0.05f, 0.03f); b.box(0.45f, 0.3f, 2.14f, 0.08f, 0.05f, 0.03f)
        // full-width tail light bar, headlights
        b.color(0xff2416, 1)
        b.box(0f, 0.62f, 2.09f, 0.82f, 0.045f, 0.03f)
        b.color(0xfff3c4, 1)
        b.box(-0.64f, 0.56f, -2.1f, 0.2f, 0.06f, 0.03f); b.box(0.64f, 0.56f, -2.1f, 0.2f, 0.06f, 0.03f)
    }

    private fun van(b: MeshBuilder) {
        wheels(b, 0.93f, -1.6f, 1.5f, 0.36f, 0.14f)
        b.color(0xffffff, 2)
        b.frustum(1.04f, 0.3f, -2.45f, 2.45f, 1.04f, 1.05f, -2.5f, 2.5f)
        b.frustum(1.04f, 1.05f, -1.35f, 2.5f, 1.0f, 2.0f, -1.3f, 2.45f)
        b.color(0x1b2732)
        b.frustum(1.0f, 1.05f, -2.5f, -1.35f, 0.94f, 1.96f, -1.75f, -1.3f)
        b.box(0f, 1.5f, 2.47f, 0.7f, 0.3f, 0.02f)
        b.color(0x22252b)
        b.box(0f, 0.4f, -2.5f, 1.0f, 0.1f, 0.04f); b.box(0f, 0.4f, 2.5f, 1.0f, 0.1f, 0.04f)
        lights(b, -2.52f, 2.52f, 0.78f, 0.74f)
    }

    private fun truck(b: MeshBuilder) {
        // wheels: front axle + two rear axles
        wheels(b, 1.05f, -3.5f, 3.2f, 0.48f, 0.17f)
        wheels(b, 1.05f, -3.5f, 2.0f, 0.48f, 0.17f)
        b.color(0x2a2d33)
        b.box(0f, 0.75f, 0.3f, 1.0f, 0.14f, 4.2f)
        // cab
        b.color(0xffffff, 2)
        b.frustum(1.16f, 0.6f, -4.6f, -2.7f, 1.16f, 1.6f, -4.6f, -2.7f)
        b.frustum(1.16f, 1.6f, -4.6f, -2.7f, 1.1f, 2.5f, -4.35f, -2.7f)
        b.color(0x1b2732)
        b.box(0f, 2.02f, -4.5f, 0.98f, 0.33f, 0.03f)
        b.box(-1.15f, 2.0f, -3.7f, 0.02f, 0.3f, 0.5f); b.box(1.15f, 2.0f, -3.7f, 0.02f, 0.3f, 0.5f)
        // cargo box
        b.color(0xdcdcd4)
        b.box(0f, 2.05f, 1.05f, 1.24f, 1.2f, 3.55f)
        b.color(0xb9b9b0)
        b.box(0f, 2.05f, 4.61f, 1.1f, 1.05f, 0.01f)
        b.color(0x22252b)
        b.box(0f, 0.6f, 4.62f, 1.2f, 0.1f, 0.05f); b.box(0f, 0.5f, -4.62f, 1.16f, 0.14f, 0.05f)
        lights(b, -4.64f, 4.64f, 0.82f, 0.9f)
    }

    private fun cone(b: MeshBuilder) {
        b.color(0x26282c)
        b.box(0f, 0.025f, 0f, 0.27f, 0.025f, 0.27f)
        b.color(0xff6a13)
        b.cone(0f, 0.05f, 0f, 0.2f, 0.3f, 0.14f, 6)
        b.color(0xf7f7f2)
        b.cone(0f, 0.3f, 0f, 0.14f, 0.48f, 0.09f, 6)
        b.color(0xff6a13)
        b.cone(0f, 0.48f, 0f, 0.09f, 0.74f, 0f, 6)
    }

    private fun barrier(b: MeshBuilder) {
        b.color(0x3a3d44)
        for (sx in intArrayOf(-1, 1)) {
            b.box(sx * 1.2f, 0.5f, 0f, 0.05f, 0.5f, 0.05f)
            b.box(sx * 1.2f, 0.04f, 0f, 0.07f, 0.04f, 0.34f)
        }
        for (i in 0 until 6) {
            b.color(if (i % 2 == 0) 0xe2231a else 0xf5f5f0)
            val x = -1.45f + 0.2416f + i * 0.4833f
            b.box(x, 0.86f, 0f, 0.2417f, 0.16f, 0.04f)
            b.color(if (i % 2 == 1) 0xe2231a else 0xf5f5f0)
            b.box(x, 0.46f, 0f, 0.2417f, 0.08f, 0.035f)
        }
        b.color(0xffb300, 1)
        b.box(-1.2f, 1.1f, 0f, 0.08f, 0.08f, 0.06f); b.box(1.2f, 1.1f, 0f, 0.08f, 0.08f, 0.06f)
    }

    /** Arrow board; the chevrons point towards +x (mirror with a negative x scale for the other lane). */
    private fun arrowBoard(b: MeshBuilder) {
        b.color(0x33363c)
        b.box(0f, 0.3f, 0f, 0.9f, 0.14f, 0.4f)
        b.box(0f, 1.0f, 0f, 0.07f, 0.6f, 0.07f)
        b.color(0x15161a)
        b.box(0f, 2.1f, 0f, 1.0f, 0.62f, 0.05f)
        b.color(0xffb300, 1)
        // two chevrons made of small lamps, on the side facing the driver (+z)
        for (c in 0 until 2) {
            val bx = -0.5f + c * 0.62f
            for (k in -2..2) {
                val ax = bx + (2 - (if (k < 0) -k else k)) * 0.17f
                b.box(ax, 2.1f + k * 0.2f, 0.06f, 0.07f, 0.07f, 0.02f)
            }
        }
    }

    private fun coin(b: MeshBuilder) {
        b.color(0xd99a12, 1)
        b.disc(0f, 0.95f, 0f, 0.52f, 0.06f, 10)
        b.color(0xffd43a, 1)
        b.disc(0f, 0.95f, 0f, 0.44f, 0.075f, 10)
        b.color(0xfff0a0, 1)
        b.disc(0f, 0.95f, 0f, 0.2f, 0.085f, 4)
    }

    private fun shieldPickup(b: MeshBuilder) {
        val r = 0.6f; val cy = 1.05f
        val cols = intArrayOf(0x49b6ff, 0x2a8fe8, 0x7fd0ff, 0x1c6fd0)
        for (i in 0 until 4) {
            val a0 = i * 1.5707964f; val a1 = a0 + 1.5707964f
            val x0 = kotlin.math.cos(a0) * r; val z0 = kotlin.math.sin(a0) * r
            val x1 = kotlin.math.cos(a1) * r; val z1 = kotlin.math.sin(a1) * r
            b.color(cols[i], 1)
            b.tri(x0, cy, z0, x1, cy, z1, 0f, cy + 0.8f, 0f, x0 + x1, 0.5f, z0 + z1)
            b.color(cols[(i + 2) % 4], 1)
            b.tri(x0, cy, z0, x1, cy, z1, 0f, cy - 0.8f, 0f, x0 + x1, -0.5f, z0 + z1)
        }
    }

    /** Faceted sphere of radius 1 centred at the origin (drawn translucent around the car). */
    private fun bubble(b: MeshBuilder) {
        val rings = 5; val seg = 10
        for (i in 0 until rings) {
            val t0 = -1.5707964f + i * 3.1415927f / rings; val t1 = t0 + 3.1415927f / rings
            val y0 = kotlin.math.sin(t0); val y1 = kotlin.math.sin(t1)
            val r0 = kotlin.math.cos(t0); val r1 = kotlin.math.cos(t1)
            for (j in 0 until seg) {
                val a0 = j * 6.2831855f / seg; val a1 = a0 + 6.2831855f / seg
                val c0 = kotlin.math.cos(a0); val s0 = kotlin.math.sin(a0)
                val c1 = kotlin.math.cos(a1); val s1 = kotlin.math.sin(a1)
                val shade = 0.75f + 0.25f * (((i + j) % 2).toFloat())
                b.colorF(0.35f * shade, 0.75f * shade, 1f, 1)
                b.quad(
                    c0 * r0, y0, s0 * r0, c1 * r0, y0, s1 * r0, c1 * r1, y1, s1 * r1, c0 * r1, y1, s0 * r1,
                    c0 + c1, y0 + y1, s0 + s1,
                )
            }
        }
    }

    companion object {
        const val PLAYER = 0
        const val CAR = 1
        const val VAN = 2
        const val TRUCK = 3
        const val CONE = 4
        const val BARRIER = 5
        const val SIGN = 6
        const val COIN = 7
        const val SHIELD = 8
        const val BUBBLE = 9
        const val COUNT = 10

        /** Body paint colours for traffic (indexed by Obstacle.color). */
        val PAINT = floatArrayOf(
            0.86f, 0.14f, 0.12f, 0.95f, 0.95f, 0.93f, 0.13f, 0.35f, 0.75f, 0.96f, 0.74f, 0.1f,
            0.2f, 0.62f, 0.38f, 0.15f, 0.16f, 0.19f, 0.6f, 0.63f, 0.68f, 0.55f, 0.25f, 0.65f,
        )
    }
}
