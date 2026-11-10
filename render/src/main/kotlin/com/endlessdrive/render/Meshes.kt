package com.endlessdrive.render

import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural low-poly model library. CPU-side builders are kept (scenery is baked into road
 * chunks); GPU meshes are created in [upload] (again after an EGL context loss).
 * Local axes: +Z forward, +Y up, X lateral.
 */
class Meshes {
    val player = MeshBuilder()
    val sedans = Array(SEDAN_COLORS.size) { MeshBuilder() }
    val suvs = Array(SUV_COLORS.size) { MeshBuilder() }
    val trucks = Array(TRUCK_CABS.size) { MeshBuilder() }
    val cone = MeshBuilder()
    val barrier = MeshBuilder()
    val patch = MeshBuilder()
    val workSign = MeshBuilder()
    val coin = MeshBuilder()
    val shield = MeshBuilder()
    val pine = MeshBuilder()
    val broadleaf = MeshBuilder()
    val bush = MeshBuilder()
    val buildings = Array(3) { MeshBuilder() }
    val lamp = MeshBuilder()
    val billboard = MeshBuilder()
    val mountains = MeshBuilder()
    val sky = MeshBuilder()

    val gPlayer = Mesh()
    val gSedans = Array(SEDAN_COLORS.size) { Mesh() }
    val gSuvs = Array(SUV_COLORS.size) { Mesh() }
    val gTrucks = Array(TRUCK_CABS.size) { Mesh() }
    val gCone = Mesh()
    val gBarrier = Mesh()
    val gPatch = Mesh()
    val gWorkSign = Mesh()
    val gCoin = Mesh()
    val gShield = Mesh()
    val gMountains = Mesh()
    val gSky = Mesh()

    init {
        buildPlayer(player)
        for (i in sedans.indices) buildSedan(sedans[i], SEDAN_COLORS[i])
        for (i in suvs.indices) buildSuv(suvs[i], SUV_COLORS[i])
        for (i in trucks.indices) buildTruck(trucks[i], TRUCK_CABS[i], TRUCK_BOXES[i])
        buildCone(cone)
        buildBarrier(barrier)
        patch.color(0x5D4632).box(0f, 0.015f, 0f, 1f, 0.015f, 1f)
        buildWorkSign(workSign)
        coin.color(0xFFC107, 0.45f).cylinderX(0f, 0f, 0f, 0.45f, 0.07f, 14)
        coin.color(0xFFE082, 0.6f).cylinderX(0f, 0f, 0f, 0.3f, 0.09f, 10)
        shield.color(0x00B8D4, 0.7f).blob(0f, 0f, 0f, 0.55f, 0.55f, 0.55f, 4, 8)
        shield.color(0xE0F7FA, 1f).blob(0f, 0f, 0f, 0.3f, 0.3f, 0.3f, 3, 6)
        buildPine(pine)
        buildBroadleaf(broadleaf)
        bush.color(0x4E7F2C).blob(0f, 0.45f, 0f, 1.0f, 0.7f, 1.0f, 3, 6)
        for (i in buildings.indices) buildBuilding(buildings[i], i)
        buildLamp(lamp)
        buildBillboard(billboard)
        buildMountains(mountains)
        sky.color(0xFFFFFF).blob(0f, 0f, 0f, 1f, 1f, 1f, 8, 16)
    }

    fun upload(gl: GL) {
        gPlayer.uploadStatic(gl, player)
        for (i in sedans.indices) gSedans[i].uploadStatic(gl, sedans[i])
        for (i in suvs.indices) gSuvs[i].uploadStatic(gl, suvs[i])
        for (i in trucks.indices) gTrucks[i].uploadStatic(gl, trucks[i])
        gCone.uploadStatic(gl, cone)
        gBarrier.uploadStatic(gl, barrier)
        gPatch.uploadStatic(gl, patch)
        gWorkSign.uploadStatic(gl, workSign)
        gCoin.uploadStatic(gl, coin)
        gShield.uploadStatic(gl, shield)
        gMountains.uploadStatic(gl, mountains)
        gSky.uploadStatic(gl, sky)
    }

    /** Mesh for a traffic car variant (0..15). */
    fun car(variant: Int): Mesh =
        if (variant % 3 == 2) gSuvs[(variant / 3) % gSuvs.size] else gSedans[variant % gSedans.size]

    // ------------------------------------------------------------------ vehicles

    private fun wheels(b: MeshBuilder, x: Float, z0: Float, z1: Float, r: Float) {
        b.color(0x1A1A1C)
        b.cylinderX(-x, r, z0, r, 0.13f, 8); b.cylinderX(x, r, z0, r, 0.13f, 8)
        b.cylinderX(-x, r, z1, r, 0.13f, 8); b.cylinderX(x, r, z1, r, 0.13f, 8)
        b.color(0x9EA4AA)
        b.cylinderX(-x - 0.135f, r, z0, r * 0.45f, 0.01f, 6); b.cylinderX(x + 0.135f, r, z0, r * 0.45f, 0.01f, 6)
        b.cylinderX(-x - 0.135f, r, z1, r * 0.45f, 0.01f, 6); b.cylinderX(x + 0.135f, r, z1, r * 0.45f, 0.01f, 6)
    }

    private fun lights(b: MeshBuilder, hw: Float, hl: Float, y: Float) {
        b.color(0xFFF4D6, 1f)
        b.box(-hw * 0.66f, y, hl + 0.005f, 0.2f, 0.06f, 0.02f); b.box(hw * 0.66f, y, hl + 0.005f, 0.2f, 0.06f, 0.02f)
        b.color(0xE31B1B, 0.9f)
        b.box(-hw * 0.66f, y + 0.02f, -hl - 0.005f, 0.22f, 0.06f, 0.02f); b.box(hw * 0.66f, y + 0.02f, -hl - 0.005f, 0.22f, 0.06f, 0.02f)
    }

    private fun buildSedan(b: MeshBuilder, body: Int) {
        b.color(body).taper(0f, 0.56f, 0f, 0.92f, 0.27f, 2.2f, 0.9f, 2.08f, 0f)
        b.color(0x18222C).taper(0f, 1.07f, -0.2f, 0.84f, 0.24f, 1.05f, 0.7f, 0.62f, -0.08f)
        b.color(body).box(0f, 1.32f, -0.28f, 0.71f, 0.02f, 0.63f)
        b.color(0x2A2D33).box(0f, 0.36f, 2.18f, 0.9f, 0.09f, 0.06f).box(0f, 0.36f, -2.18f, 0.9f, 0.09f, 0.06f)
        lights(b, 0.92f, 2.2f, 0.66f)
        wheels(b, 0.8f, -1.35f, 1.35f, 0.33f)
    }

    private fun buildSuv(b: MeshBuilder, body: Int) {
        b.color(body).taper(0f, 0.68f, 0f, 0.95f, 0.36f, 2.2f, 0.93f, 2.12f, 0f)
        b.color(0x18222C).taper(0f, 1.36f, -0.35f, 0.9f, 0.32f, 1.55f, 0.82f, 1.35f, -0.1f)
        b.color(body).box(0f, 1.69f, -0.45f, 0.83f, 0.02f, 1.36f)
        b.color(0x2A2D33).box(0f, 0.4f, 2.18f, 0.93f, 0.12f, 0.06f).box(0f, 0.4f, -2.18f, 0.93f, 0.12f, 0.06f)
        lights(b, 0.95f, 2.2f, 0.85f)
        wheels(b, 0.82f, -1.4f, 1.4f, 0.38f)
    }

    private fun buildPlayer(b: MeshBuilder) {
        val body = 0xE53935
        b.color(body).taper(0f, 0.5f, 0.05f, 0.95f, 0.24f, 2.15f, 0.92f, 1.9f, -0.12f)
        b.color(0xB71C1C).taper(0f, 0.27f, 0f, 0.97f, 0.05f, 2.2f, 0.95f, 2.15f, 0f)
        b.color(0x111820).taper(0f, 0.9f, -0.35f, 0.83f, 0.17f, 0.95f, 0.66f, 0.55f, -0.15f)
        b.color(body).box(0f, 1.075f, -0.5f, 0.66f, 0.01f, 0.56f)
        b.color(0xFFFFFF).box(-0.17f, 0.745f, 1.1f, 0.07f, 0.005f, 1.0f).box(0.17f, 0.745f, 1.1f, 0.07f, 0.005f, 1.0f)
        b.color(0xFFFFFF).box(-0.17f, 1.087f, -0.5f, 0.07f, 0.003f, 0.56f).box(0.17f, 1.087f, -0.5f, 0.07f, 0.003f, 0.56f)
        b.color(0x1C1C1C).box(0f, 1.02f, -2.0f, 0.9f, 0.03f, 0.2f)
        b.box(-0.6f, 0.85f, -1.95f, 0.04f, 0.15f, 0.05f).box(0.6f, 0.85f, -1.95f, 0.04f, 0.15f, 0.05f)
        b.color(0x2A2A2A).box(0f, 0.3f, -2.2f, 0.6f, 0.08f, 0.04f)
        b.color(0xB0BEC5).cylinderX(-0.35f, 0.3f, -2.24f, 0.07f, 0.04f, 6)
        b.color(0xB0BEC5).cylinderX(0.35f, 0.3f, -2.24f, 0.07f, 0.04f, 6)
        lights(b, 0.95f, 2.2f, 0.58f)
        wheels(b, 0.84f, -1.4f, 1.35f, 0.34f)
    }

    private fun buildTruck(b: MeshBuilder, cab: Int, box: Int) {
        b.color(0x2B2E33).box(0f, 0.55f, -0.2f, 1.05f, 0.22f, 5.3f)
        b.color(cab).box(0f, 1.55f, 4.45f, 1.2f, 1.2f, 1.05f)
        b.color(0x18222C).box(0f, 2.05f, 5.51f, 1.02f, 0.42f, 0.02f)
        b.color(box).box(0f, 2.2f, -1.05f, 1.25f, 1.55f, 4.45f)
        b.color(darken(box)).box(0f, 2.2f, -1.05f, 1.255f, 0.18f, 4.3f)
        b.color(0xFFF4D6, 1f).box(-0.85f, 0.85f, 5.51f, 0.18f, 0.08f, 0.02f).box(0.85f, 0.85f, 5.51f, 0.18f, 0.08f, 0.02f)
        b.color(0xE31B1B, 0.9f).box(-0.95f, 0.9f, -5.51f, 0.16f, 0.1f, 0.02f).box(0.95f, 0.9f, -5.51f, 0.16f, 0.1f, 0.02f)
        b.color(0xFFA000, 0.6f).box(0f, 3.79f, -5.5f, 0.9f, 0.02f, 0.02f)
        wheels(b, 1.0f, 4.2f, -2.8f, 0.48f)
        wheels(b, 1.0f, -3.9f, -4.95f, 0.48f)
    }

    private fun buildCone(b: MeshBuilder) {
        b.color(0x222222).box(0f, 0.02f, 0f, 0.24f, 0.02f, 0.24f)
        b.color(0xFF6D00, 0.15f).cone(0f, 0.04f, 0.78f, 0f, 0.19f, 8)
        b.color(0xF5F5F5, 0.3f).cylinderY(0f, 0.36f, 0.47f, 0f, 0.125f, 8, false)
    }

    private fun buildBarrier(b: MeshBuilder) {
        b.color(0x8D9399).box(-1.35f, 0.45f, 0f, 0.06f, 0.45f, 0.25f).box(1.35f, 0.45f, 0f, 0.06f, 0.45f, 0.25f)
        val seg = 3.2f / 6f
        for (i in 0 until 6) {
            b.color(if (i % 2 == 0) 0xE53935 else 0xFAFAFA, 0.1f)
            b.box(-1.6f + seg * (i + 0.5f), 0.78f, 0f, seg / 2f, 0.16f, 0.06f)
        }
        b.color(0xFFB300, 1f).box(-1.35f, 1.04f, 0f, 0.09f, 0.09f, 0.07f).box(1.35f, 1.04f, 0f, 0.09f, 0.09f, 0.07f)
    }

    private fun buildWorkSign(b: MeshBuilder) {
        b.color(0x8D9399).box(0f, 0.7f, 0f, 0.04f, 0.7f, 0.04f)
        b.color(0xE53935, 0.15f)
        b.tri(-0.6f, 1.2f, 0.05f, 0.6f, 1.2f, 0.05f, 0f, 2.25f, 0.05f, 0f, 1.5f, -1f)
        b.color(0xFFFFFF, 0.25f)
        b.tri(-0.42f, 1.32f, 0.07f, 0.42f, 1.32f, 0.07f, 0f, 2.05f, 0.07f, 0f, 1.5f, -1f)
        b.color(0x111111).box(0f, 1.55f, 0.08f, 0.08f, 0.14f, 0.005f)
    }

    // ------------------------------------------------------------------ scenery

    private fun buildPine(b: MeshBuilder) {
        b.color(0x5D4037).cylinderY(0f, 0f, 1.4f, 0f, 0.2f, 6)
        b.color(0x2E5E3A).cone(0f, 0.9f, 4.2f, 0f, 1.6f, 7)
        b.color(0x37704A).cone(0f, 2.6f, 5.9f, 0f, 1.15f, 7)
        b.color(0x418553).cone(0f, 4.1f, 7.0f, 0f, 0.7f, 6)
    }

    private fun buildBroadleaf(b: MeshBuilder) {
        b.color(0x6D4C41).cylinderY(0f, 0f, 2.4f, 0f, 0.24f, 6)
        b.color(0x5B8C2A).blob(0f, 3.4f, 0f, 2.0f, 1.7f, 2.0f, 4, 7)
        b.color(0x6FA336).blob(0.6f, 4.3f, 0.3f, 1.2f, 1.0f, 1.2f, 3, 6)
    }

    private fun buildBuilding(b: MeshBuilder, variant: Int) {
        val wall = intArrayOf(0xD7CCC8, 0x90A4AE, 0xBCAAA4)[variant]
        val roof = intArrayOf(0x8D6E63, 0x546E7A, 0x6D4C41)[variant]
        b.color(wall).box(0f, 0.5f, 0f, 0.5f, 0.5f, 0.5f)
        b.color(roof).box(0f, 1.01f, 0f, 0.52f, 0.01f, 0.52f)
        val floors = 3 + variant * 2
        for (f in 0 until floors) {
            val y = (f + 0.55f) / (floors + 0.3f)
            val lit = if ((f + variant) % 3 == 0) 0.25f else 1f
            b.color(0xFFE6A6, lit)
            b.box(0f, y, 0.502f, 0.4f, 0.32f / (floors + 0.3f), 0.003f)
            b.box(0f, y, -0.502f, 0.4f, 0.32f / (floors + 0.3f), 0.003f)
            b.box(0.502f, y, 0f, 0.003f, 0.32f / (floors + 0.3f), 0.4f)
            b.box(-0.502f, y, 0f, 0.003f, 0.32f / (floors + 0.3f), 0.4f)
        }
    }

    private fun buildLamp(b: MeshBuilder) {
        b.color(0x6F7780).cylinderY(0f, 0f, 7.2f, 0f, 0.09f, 6)
        b.box(1.0f, 7.15f, 0f, 1.0f, 0.06f, 0.06f)
        b.color(0xFFE0A0, 1f).box(1.9f, 7.05f, 0f, 0.28f, 0.05f, 0.14f)
    }

    private fun buildBillboard(b: MeshBuilder) {
        b.color(0x6F7780).box(-1.6f, 1.6f, 0f, 0.08f, 1.6f, 0.08f).box(1.6f, 1.6f, 0f, 0.08f, 1.6f, 0.08f)
        b.color(0xF5F5F5, 0.2f).box(0f, 3.9f, 0f, 2.4f, 1.0f, 0.06f)
        b.color(0x1B6E3A, 0.2f).box(0f, 3.9f, 0.065f, 2.28f, 0.88f, 0.005f).box(0f, 3.9f, -0.065f, 2.28f, 0.88f, 0.005f)
        b.color(0xFFFFFF, 0.3f)
        b.box(-0.4f, 4.25f, 0.072f, 1.5f, 0.12f, 0.002f).box(0.3f, 3.85f, 0.072f, 1.2f, 0.1f, 0.002f)
        b.box(-0.7f, 3.5f, 0.072f, 0.9f, 0.1f, 0.002f)
    }

    private fun buildMountains(b: MeshBuilder) {
        val n = 56
        val rnd = java.util.Random(4)
        val h = FloatArray(n) { 0.05f + rnd.nextFloat() * 0.13f }
        for (i in 0 until n) {
            val a0 = i * 6.2831855f / n
            val a1 = (i + 1) * 6.2831855f / n
            val am = (a0 + a1) / 2f
            val x0 = cos(a0); val z0 = sin(a0); val x1 = cos(a1); val z1 = sin(a1)
            val hm = (h[i] + h[(i + 1) % n]) / 2f + 0.04f
            b.color(if (i % 2 == 0) 0x55697A else 0x4C5E6E)
            b.tri(x0, -0.02f, z0, x1, -0.02f, z1, cos(am) * 0.97f, hm, sin(am) * 0.97f, 0f, 0.05f, 0f)
            if (hm > 0.14f) {
                b.color(0xE8EEF2)
                val t = 0.82f
                b.tri(
                    cos(am) * 0.97f + (x0 - cos(am) * 0.97f) * (1 - t), hm * t, sin(am) * 0.97f + (z0 - sin(am) * 0.97f) * (1 - t),
                    cos(am) * 0.97f + (x1 - cos(am) * 0.97f) * (1 - t), hm * t, sin(am) * 0.97f + (z1 - sin(am) * 0.97f) * (1 - t),
                    cos(am) * 0.969f, hm + 0.001f, sin(am) * 0.969f, 0f, 0.05f, 0f,
                )
            }
        }
    }

    companion object {
        val SEDAN_COLORS = intArrayOf(0x1E88E5, 0xFDD835, 0xECEFF1, 0x43A047, 0x8E24AA, 0x37474F)
        val SUV_COLORS = intArrayOf(0x263238, 0xB0BEC5, 0x00897B, 0x6D4C41)
        val TRUCK_CABS = intArrayOf(0x1565C0, 0x2E7D32, 0xF9A825)
        val TRUCK_BOXES = intArrayOf(0xF5F5F5, 0xCFD8DC, 0xEF6C00)

        fun darken(c: Int): Int {
            val r = ((c shr 16) and 255) * 3 / 4
            val g = ((c shr 8) and 255) * 3 / 4
            val b = (c and 255) * 3 / 4
            return (r shl 16) or (g shl 8) or b
        }
    }
}
