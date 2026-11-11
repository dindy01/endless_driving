package com.endlessdrive.engine

import com.endlessdrive.core.Cfg
import com.endlessdrive.core.Kind
import com.endlessdrive.core.Pickup
import com.endlessdrive.core.Rng
import com.endlessdrive.core.RoadFrame
import com.endlessdrive.core.World
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the world. Platform independent (see [Gl]); allocates nothing per frame.
 * All positions sent to the GPU are relative to the camera, so precision never degrades however
 * far the player drives.
 */
class Renderer(quality: Quality) {
    var quality: Quality = quality
        set(q) {
            field = q
            invalidateChunks()
        }

    private lateinit var gl: Gl
    private val models = Models()
    private val mesher = ChunkMesher()
    private val chunks = Array(MAX_CHUNKS) { Chunk() }
    private val batch = Batch()
    private var modelVbo = 0
    private var scratch: FloatBuffer = direct(16000 * MeshBuilder.STRIDE)

    private var lit = 0
    private var uMvp = 0; private var uModel = 0; private var uSunDir = 0; private var uSunCol = 0
    private var uAmbient = 0; private var uTint = 0; private var uFogCol = 0; private var uParams = 0
    private var uHeadPos = 0; private var uHeadDir = 0; private var uAlpha = 0
    private var unlit = 0
    private var uUnlitMvp = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ident = FloatArray(16).also { Mat4.identity(it) }
    private val basis = FloatArray(9)
    private val f = RoadFrame()
    private val fx = Rng(5)

    private var width = 1
    private var height = 1
    private var clock = 0f
    private var boostSmooth = 0f
    private var frame = 0

    // camera (world space, doubles) and scratch outputs of locate()
    private var camX = 0.0; private var camY = 0.0; private var camZ = 0.0
    private var ox = 0f; private var oy = 0f; private var oz = 0f
    private var sinH = 0f; private var cosH = 1f; private var slope = 0f

    // blended environment
    private val zen = FloatArray(3); private val hor = FloatArray(3)
    private val sun = FloatArray(3); private val amb = FloatArray(3); private val dir = FloatArray(3)
    private var fogFrac = 0.4f
    private var glowK = 0f

    private val starDir = FloatArray(STARS * 3)
    private val ridge = FloatArray(RIDGE + 1)
    private val ridge2 = FloatArray(RIDGE + 1)
    private val cloud = FloatArray(CLOUDS * 4)
    private val rainX = FloatArray(MAX_RAIN); private val rainY = FloatArray(MAX_RAIN); private val rainZ = FloatArray(MAX_RAIN)

    init {
        val r = Rng(1234)
        for (i in 0 until STARS) {
            val az = r.range(0f, 6.2831855f); val el = r.range(0.1f, 1.45f)
            starDir[i * 3] = cos(el) * sin(az); starDir[i * 3 + 1] = sin(el); starDir[i * 3 + 2] = -cos(el) * cos(az)
        }
        for (i in 0..RIDGE) {
            val a = i * 6.2831855f / RIDGE
            ridge[i] = 2.2f + 1.6f * sin(a * 3f + 1f) + 1.3f * sin(a * 7f + 2f) + r.range(-0.5f, 0.9f)
            ridge2[i] = 4.2f + 2.4f * sin(a * 2f + 4f) + 1.8f * sin(a * 5f + 0.5f) + r.range(-0.6f, 1.4f)
        }
        ridge[RIDGE] = ridge[0]; ridge2[RIDGE] = ridge2[0]
        for (i in 0 until CLOUDS) {
            cloud[i * 4] = r.range(0f, 6.2831855f); cloud[i * 4 + 1] = r.range(0.2f, 0.75f)
            cloud[i * 4 + 2] = r.range(7f, 15f); cloud[i * 4 + 3] = r.range(0.6f, 1f)
        }
        for (i in 0 until MAX_RAIN) {
            rainX[i] = r.range(-11f, 11f); rainY[i] = r.range(-5f, 6f); rainZ[i] = r.range(-30f, -0.6f)
        }
    }

    private fun direct(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** (Re)creates every GL resource. Call whenever a GL context is created (including after context loss). */
    fun init(gl: Gl) {
        this.gl = gl
        lit = gl.createProgram(gl.vertexPrefix + LIT_VS, gl.fragmentPrefix + LIT_FS, arrayOf("aPos", "aNormal", "aColor", "aMat"))
        uMvp = gl.uniformLocation(lit, "uMVP"); uModel = gl.uniformLocation(lit, "uModel")
        uSunDir = gl.uniformLocation(lit, "uSunDir"); uSunCol = gl.uniformLocation(lit, "uSunCol")
        uAmbient = gl.uniformLocation(lit, "uAmbient"); uTint = gl.uniformLocation(lit, "uTint")
        uFogCol = gl.uniformLocation(lit, "uFogCol"); uParams = gl.uniformLocation(lit, "uParams")
        uHeadPos = gl.uniformLocation(lit, "uHeadPos"); uHeadDir = gl.uniformLocation(lit, "uHeadDir")
        uAlpha = gl.uniformLocation(lit, "uAlpha")
        unlit = gl.createProgram(gl.vertexPrefix + UNLIT_VS, gl.fragmentPrefix + UNLIT_FS, arrayOf("aPos", "aColor"))
        uUnlitMvp = gl.uniformLocation(unlit, "uMVP")

        modelVbo = gl.createBuffer()
        gl.bindArrayBuffer(modelVbo)
        upload(models.builder.data, models.builder.vertexCount * MeshBuilder.STRIDE, false)
        for (c in chunks) {
            c.vbo = gl.createBuffer()
            c.index = Int.MIN_VALUE
            c.generation = -1
        }
        batch.gl = gl
        batch.vbo = gl.createBuffer()
    }

    fun resize(w: Int, h: Int) {
        width = if (w < 1) 1 else w
        height = if (h < 1) 1 else h
    }

    private fun invalidateChunks() {
        for (c in chunks) c.generation = -1
    }

    private fun upload(data: FloatArray, floats: Int, dynamic: Boolean) {
        if (scratch.capacity() < floats) scratch = direct(floats + floats / 2)
        scratch.clear()
        scratch.put(data, 0, floats)
        scratch.position(0)
        gl.bufferData(scratch, floats, dynamic)
    }

    // ------------------------------------------------------------------ helpers

    /** Camera-relative position of road-space point (s, lateral, height) plus the road direction there. */
    private fun locate(world: World, s: Double, lat: Float, up: Float) {
        world.road.frame(s, f)
        sinH = sin(f.heading); cosH = cos(f.heading); slope = f.slope
        ox = (f.x + cosH * lat - camX).toFloat()
        oy = (f.y + up - camY).toFloat()
        oz = (f.z + sinH * lat - camZ).toFloat()
    }

    private fun bindLit(vbo: Int) {
        gl.bindArrayBuffer(vbo)
        val st = MeshBuilder.STRIDE * 4
        gl.vertexAttribPointer(0, 3, st, 0)
        gl.vertexAttribPointer(1, 3, st, 12)
        gl.vertexAttribPointer(2, 3, st, 24)
        gl.vertexAttribPointer(3, 1, st, 36)
    }

    private fun useLit() {
        gl.useProgram(lit)
        gl.enableVertexAttrib(2); gl.enableVertexAttrib(3)
    }

    private fun useUnlit(m: FloatArray) {
        gl.useProgram(unlit)
        gl.disableVertexAttrib(2); gl.disableVertexAttrib(3)
        gl.uniformMatrix4(uUnlitMvp, m)
    }

    private fun drawModel(id: Int, yaw: Float, roll: Float, sx: Float, sy: Float, sz: Float, tr: Float, tg: Float, tb: Float) {
        var sh = sinH; var ch = cosH
        if (yaw != 0f) {
            val s = sin(yaw); val c = cos(yaw)
            sh = sinH * c + cosH * s; ch = cosH * c - sinH * s
        }
        Mat4.roadObject(model, ox, oy, oz, sh, ch, slope, roll, sx, sy, sz)
        Mat4.mul(mvp, vp, model)
        gl.uniformMatrix4(uMvp, mvp)
        gl.uniformMatrix4(uModel, model)
        gl.uniform3f(uTint, tr, tg, tb)
        gl.drawTriangles(models.first[id], models.count[id])
    }

    private fun blendEnv(world: World) {
        val w = world.env.w
        for (c in 0 until 3) {
            var z = 0f; var h = 0f; var s = 0f; var a = 0f; var d = 0f
            for (p in 0 until 4) {
                z += ZEN[p * 3 + c] * w[p]; h += HOR[p * 3 + c] * w[p]; s += SUN[p * 3 + c] * w[p]
                a += AMB[p * 3 + c] * w[p]; d += DIR[p * 3 + c] * w[p]
            }
            zen[c] = z; hor[c] = h; sun[c] = s; amb[c] = a; dir[c] = d
        }
        val l = 1f / sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
        dir[0] *= l; dir[1] *= l; dir[2] *= l
        fogFrac = FOGF[0] * w[0] + FOGF[1] * w[1] + FOGF[2] * w[2] + FOGF[3] * w[3]
        val g = world.env.night + 0.55f * world.env.dusk + 0.35f * world.env.rain
        glowK = if (g > 1f) 1f else g
    }

    // ------------------------------------------------------------------ frame

    fun draw(world: World, dt: Float) {
        clock += dt
        frame++
        val boostTarget = if (world.boosting) 1f else 0f
        boostSmooth += (boostTarget - boostSmooth) * (1f - exp(-dt * 5f))
        world.particles.limit = minOf(world.particles.capacity, quality.particles)
        blendEnv(world)
        setupCamera(world)

        gl.viewport(0, 0, width, height)
        gl.disable(Gl.CULL_FACE)
        gl.depthMask(true)
        gl.clearColor(hor[0], hor[1], hor[2], 1f)
        gl.clear(Gl.COLOR_BUFFER_BIT or Gl.DEPTH_BUFFER_BIT)
        gl.enableVertexAttrib(0); gl.enableVertexAttrib(1)

        drawSky(world)

        // ---- opaque world
        gl.disable(Gl.BLEND)
        gl.enable(Gl.DEPTH_TEST)
        gl.depthMask(true)
        useLit()
        val dd = quality.drawDistance
        val fogStart = dd * fogFrac
        gl.uniform3f(uSunDir, dir[0], dir[1], dir[2])
        gl.uniform3f(uSunCol, sun[0], sun[1], sun[2])
        gl.uniform3f(uAmbient, amb[0], amb[1], amb[2])
        gl.uniform3f(uFogCol, hor[0], hor[1], hor[2])
        gl.uniform1f(uAlpha, 1f)
        // headlights: origin at the player's nose, pointing along the car
        locate(world, world.s, world.x, 0.7f)
        val hy = world.yaw
        val hs = sinH * cos(hy) + cosH * sin(hy); val hc = cosH * cos(hy) - sinH * sin(hy)
        gl.uniform3f(uHeadPos, ox + hs * 1.6f, oy, oz - hc * 1.6f)
        gl.uniform3f(uHeadDir, hs, slope, -hc)
        val head = if (world.state == World.OVER || world.state == World.CRASHING) glowK * 0.5f else glowK
        gl.uniform4f(uParams, fogStart, 1f / (dd * 0.97f - fogStart), world.env.night, head * 2.6f)

        drawChunks(world)
        drawObjects(world)

        // ---- translucent world-space effects
        gl.enable(Gl.BLEND)
        gl.depthMask(false)
        useUnlit(vp)
        gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE_MINUS_SRC_ALPHA)
        if (quality.shadows > 0) drawShadows(world)
        drawParticles(world, false)
        batch.flush()
        gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE)
        drawGlows(world)
        drawParticles(world, true)
        batch.flush()

        // ---- shield bubble
        if (world.shieldTime > 0f && (world.shieldTime > 1.2f || (clock * 8f).toInt() % 2 == 0)) {
            gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE)
            useLit()
            bindLit(modelVbo)
            gl.uniform1f(uAlpha, 0.2f + 0.06f * sin(clock * 6f))
            locate(world, world.s, world.x, 0.75f)
            drawModel(Models.BUBBLE, clock * 0.8f, 0f, 1.55f, 1.2f, 2.8f, 1f, 1f, 1f)
            gl.uniform1f(uAlpha, 1f)
        }

        // ---- view-space and screen-space effects
        gl.disable(Gl.DEPTH_TEST)
        gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE_MINUS_SRC_ALPHA)
        if (world.env.rain > 0.02f) {
            useUnlit(proj)
            drawRain(world, dt)
            batch.flush()
        }
        useUnlit(ident)
        drawSpeedLines(world)
        if (world.state == World.CRASHING && world.crashTimer < 0.16f) {
            val a = 0.65f * (1f - world.crashTimer / 0.16f)
            batch.quad(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f, 1f, 0.95f, 0.85f, a)
        }
        batch.flush()
        gl.depthMask(true)
        gl.disable(Gl.BLEND)
    }

    private fun setupCamera(world: World) {
        val road = world.road
        var lx: Double; var ly: Double; var lz: Double
        var roll = 0f
        if (world.state == World.ATTRACT) {
            val orbit = 0.75f * sin(clock * 0.17f)
            road.frame(maxOf(1.0, world.s - 7.5 * cos(orbit)), f)
            val lat = world.x + 6.5f * sin(orbit)
            camX = f.x + cos(f.heading) * lat; camZ = f.z + sin(f.heading) * lat
            camY = f.y + 2.0 + 0.5 * sin(clock * 0.11f)
            road.frame(world.s + 2.0, f)
            lx = f.x + cos(f.heading) * world.x; lz = f.z + sin(f.heading) * world.x; ly = f.y + 0.8
        } else {
            road.frame(maxOf(1.0, world.s - 7.4), f)
            val lat = world.x * 0.86f
            camX = f.x + cos(f.heading) * lat; camZ = f.z + sin(f.heading) * lat
            camY = f.y + 2.95
            road.frame(world.s + 18.0, f)
            val tl = world.x * 0.62f
            lx = f.x + cos(f.heading) * tl; lz = f.z + sin(f.heading) * tl; ly = f.y + 0.9
            roll = -world.steer * 0.025f
            // shake: crash impact, shoulder rumble, nitro buzz
            var amp = 0f
            if (world.state == World.CRASHING || world.state == World.OVER) amp = 0.55f * exp(-world.crashTimer * 3.2f)
            else {
                if (world.onShoulder) amp += 0.035f
                amp += 0.012f * boostSmooth
            }
            if (amp > 0.001f) {
                camX += fx.range(-amp, amp); camY += fx.range(-amp, amp) * 0.7; camZ += fx.range(-amp, amp)
                roll += fx.range(-amp, amp) * 0.05f
            }
        }
        val fov = if (world.state == World.ATTRACT) 46f else 50f + 13f * world.speedNorm() + 8f * boostSmooth
        Mat4.perspective(proj, fov, width.toFloat() / height, 0.4f, 1200f)
        Mat4.viewRotation(view, (lx - camX).toFloat(), (ly - camY).toFloat(), (lz - camZ).toFloat(), sin(roll), cos(roll), basis)
        Mat4.mul(vp, proj, view)
    }

    // ------------------------------------------------------------------ sky

    private fun skyV(az: Float, el: Float, r: Float, g: Float, b: Float, a: Float) {
        val ce = cos(el)
        batch.v(ce * sin(az) * DOME, sin(el) * DOME, -ce * cos(az) * DOME, r, g, b, a)
    }

    private fun drawSky(world: World) {
        gl.disable(Gl.DEPTH_TEST)
        gl.depthMask(false)
        gl.disable(Gl.BLEND)
        useUnlit(vp)
        // gradient dome
        val sectors = 16
        for (band in 0 until 4) {
            val e0 = SKY_EL[band]; val e1 = SKY_EL[band + 1]
            val t0 = SKY_T[band]; val t1 = SKY_T[band + 1]
            val r0 = hor[0] + (zen[0] - hor[0]) * t0; val g0 = hor[1] + (zen[1] - hor[1]) * t0; val b0 = hor[2] + (zen[2] - hor[2]) * t0
            val r1 = hor[0] + (zen[0] - hor[0]) * t1; val g1 = hor[1] + (zen[1] - hor[1]) * t1; val b1 = hor[2] + (zen[2] - hor[2]) * t1
            for (i in 0 until sectors) {
                val a0 = i * 6.2831855f / sectors; val a1 = a0 + 6.2831855f / sectors
                batch.reserve(6)
                skyV(a0, e0, r0, g0, b0, 1f); skyV(a1, e0, r0, g0, b0, 1f); skyV(a1, e1, r1, g1, b1, 1f)
                skyV(a0, e0, r0, g0, b0, 1f); skyV(a1, e1, r1, g1, b1, 1f); skyV(a0, e1, r1, g1, b1, 1f)
            }
        }
        batch.flush()
        gl.enable(Gl.BLEND)
        gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE)
        // stars
        val night = world.env.night
        if (night > 0.05f) {
            for (i in 0 until STARS) {
                val x = starDir[i * 3] * DOME; val y = starDir[i * 3 + 1] * DOME; val z = starDir[i * 3 + 2] * DOME
                val tw = 0.55f + 0.45f * sin(clock * (1.5f + (i % 7) * 0.4f) + i)
                val sz = 0.07f + (i % 3) * 0.035f
                batch.glow(x, y, z, sz * 2.4f, sz * 2.4f, basis[0], basis[1], basis[2], basis[3], basis[4], basis[5], 1f, 1f, 0.95f, night * tw)
            }
        }
        // sun / moon: bright core plus a soft halo
        val sx = dir[0] * DOME; val sy = dir[1] * DOME; val sz = dir[2] * DOME
        val moon = night
        val coreR = 2.3f - 0.7f * moon
        val cr = sun[0] * (1f - moon) + 0.9f * moon; val cg = sun[1] * (1f - moon) + 0.92f * moon; val cb = sun[2] * (1f - moon) + 1f * moon
        val vis = 1f - 0.85f * world.env.rain
        batch.glow(sx, sy, sz, 11f, 11f, basis[0], basis[1], basis[2], basis[3], basis[4], basis[5], cr, cg, cb, 0.45f * vis)
        batch.glow(sx, sy, sz, coreR * 1.6f, coreR * 1.6f, basis[0], basis[1], basis[2], basis[3], basis[4], basis[5], 1f, 0.98f, 0.9f, 1f * vis, 0.25f * vis)
        batch.flush()
        gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE_MINUS_SRC_ALPHA)
        // clouds: soft blobs tinted by the light
        val rain = world.env.rain
        val ca = (0.5f + 0.35f * rain) * (1f - 0.75f * night)
        val cl = 1f - 0.45f * rain
        val clr = (0.55f + 0.45f * sun[0]) * cl + 0.25f * hor[0]
        val clg = (0.55f + 0.45f * sun[1]) * cl + 0.25f * hor[1]
        val clb = (0.55f + 0.45f * sun[2]) * cl + 0.25f * hor[2]
        for (i in 0 until CLOUDS) {
            val az = cloud[i * 4] + clock * 0.004f; val el = cloud[i * 4 + 1]
            val ce = cos(el)
            val x = ce * sin(az) * DOME; val y = sin(el) * DOME; val z = -ce * cos(az) * DOME
            val w = cloud[i * 4 + 2] * (1f + 0.5f * rain)
            // tangent (horizontal) and bitangent on the dome
            batch.glow(x, y, z, w, w * 0.28f, cos(az), 0f, sin(az), -sin(el) * sin(az), ce, sin(el) * cos(az),
                minOf(1f, clr), minOf(1f, clg), minOf(1f, clb), ca * cloud[i * 4 + 3])
        }
        batch.flush()
        gl.disable(Gl.BLEND)
        // distant mountain silhouettes (two layers, infinitely far: pure parallax-free backdrop)
        ridgeLayer(ridge2, 0.3f, 0.22f)
        ridgeLayer(ridge, 0.0f, 0.42f)
        batch.flush()
    }

    private fun ridgeLayer(h: FloatArray, phase: Float, darkness: Float) {
        val mr = hor[0] * (1f - darkness) + zen[0] * 0.25f * darkness
        val mg = hor[1] * (1f - darkness) + (zen[1] * 0.3f + 0.05f) * darkness
        val mb = hor[2] * (1f - darkness) + zen[2] * 0.35f * darkness
        val rad = DOME * 0.98f
        for (i in 0 until RIDGE) {
            val a0 = phase + i * 6.2831855f / RIDGE; val a1 = phase + (i + 1) * 6.2831855f / RIDGE
            val x0 = sin(a0) * rad; val z0 = -cos(a0) * rad; val x1 = sin(a1) * rad; val z1 = -cos(a1) * rad
            batch.reserve(6)
            batch.v(x0, -4f, z0, hor[0], hor[1], hor[2], 1f); batch.v(x1, -4f, z1, hor[0], hor[1], hor[2], 1f); batch.v(x1, h[i + 1], z1, mr, mg, mb, 1f)
            batch.v(x0, -4f, z0, hor[0], hor[1], hor[2], 1f); batch.v(x1, h[i + 1], z1, mr, mg, mb, 1f); batch.v(x0, h[i], z0, mr, mg, mb, 1f)
        }
    }

    // ------------------------------------------------------------------ world

    private fun drawChunks(world: World) {
        val gen = world.road.generation
        val first = floor((world.s - 45.0) / ChunkMesher.LENGTH).toInt()
        val count = minOf(MAX_CHUNKS, ((quality.drawDistance + 45f) / ChunkMesher.LENGTH).toInt() + 2)
        var budget = 1
        for (k in first until first + count) {
            if (k < 0) continue
            val c = chunks[Math.floorMod(k, MAX_CHUNKS)]
            if (c.index != k || c.generation != gen) {
                // a fresh world (retry) rebuilds everything at once; while driving, one chunk per frame, far away in the fog
                if (c.generation == gen && budget <= 0) continue
                if (c.generation == gen) budget--
                mesher.build(world.road, world.seed, c, k, quality.scenery)
                c.generation = gen
                gl.bindArrayBuffer(c.vbo)
                upload(mesher.builder.data, c.vertexCount * MeshBuilder.STRIDE, true)
            }
            bindLit(c.vbo)
            Mat4.translation(model, (c.originX - camX).toFloat(), (-camY).toFloat(), (c.originZ - camZ).toFloat())
            Mat4.mul(mvp, vp, model)
            gl.uniformMatrix4(uMvp, mvp)
            gl.uniformMatrix4(uModel, model)
            gl.uniform3f(uTint, 1f, 1f, 1f)
            gl.drawTriangles(0, c.vertexCount)
        }
    }

    private fun drawObjects(world: World) {
        bindLit(modelVbo)
        val dd = quality.drawDistance
        val t = world.traffic
        for (i in 0 until t.count) {
            val o = t.get(i)
            if (!o.alive) continue
            val ds = (o.s - world.s).toFloat()
            if (ds > dd || ds < -40f) continue
            locate(world, o.s, o.x, 0f)
            val c = o.color * 3
            when (o.kind) {
                Kind.CAR -> drawModel(Models.CAR, 0f, 0f, 1f, 1f, 1f, Models.PAINT[c], Models.PAINT[c + 1], Models.PAINT[c + 2])
                Kind.VAN -> drawModel(Models.VAN, 0f, 0f, 1f, 1f, 1f, Models.PAINT[c], Models.PAINT[c + 1], Models.PAINT[c + 2])
                Kind.TRUCK -> drawModel(Models.TRUCK, 0f, 0f, 1f, 1f, 1f, Models.PAINT[c], Models.PAINT[c + 1], Models.PAINT[c + 2])
                Kind.CONE -> drawModel(Models.CONE, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f)
                Kind.BARRIER -> drawModel(Models.BARRIER, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f)
                else -> drawModel(Models.SIGN, 0f, 0f, if (o.lane == 0) 1f else -1f, 1f, 1f, 1f, 1f, 1f)
            }
        }
        for (p in world.pickups) {
            if (!p.active) continue
            val ds = (p.s - world.s).toFloat()
            if (ds > dd || ds < -10f) continue
            if (p.kind == Pickup.COIN) {
                locate(world, p.s, p.x, 0.1f + 0.08f * sin(clock * 4f + ds * 0.3f))
                drawModel(Models.COIN, clock * 3.2f + ds * 0.12f, 0f, 1f, 1f, 1f, 1f, 1f, 1f)
            } else {
                locate(world, p.s, p.x, 0.2f + 0.15f * sin(clock * 3f))
                drawModel(Models.SHIELD, clock * 2f, 0f, 1.15f, 1.15f, 1.15f, 1f, 1f, 1f)
            }
        }
        // player (blinks while invulnerable)
        if (world.invuln <= 0f || (clock * 12f).toInt() % 2 == 0 || world.state != World.RUNNING) {
            locate(world, world.s, world.x, 0f)
            val crashed = world.state == World.CRASHING || world.state == World.OVER
            drawModel(
                Models.PLAYER, world.yaw * 1.6f + (if (crashed) world.spin else 0f), -world.steer * 0.045f,
                1f, 1f, 1f, 1f, 0.42f, 0.06f,
            )
        }
    }

    private fun shadowQuad(hw: Float, hl: Float, a: Float, shx: Float, shz: Float) {
        val rxw = cosH * hw; val rzw = sinH * hw
        val fxl = sinH * hl; val fzl = -cosH * hl
        val x = ox + shx; val z = oz + shz; val y = oy + 0.035f
        val sl = slope * hl
        batch.quad(
            x - rxw - fxl, y - sl, z - rzw - fzl, x + rxw - fxl, y - sl, z + rzw - fzl,
            x + rxw + fxl, y + sl, z + rzw + fzl, x - rxw + fxl, y + sl, z - rzw + fzl, 0f, 0f, 0f, a,
        )
    }

    private fun drawShadows(world: World) {
        val k = (1f - 0.65f * world.env.night) * (1f - 0.5f * world.env.rain)
        val a = 0.34f * k
        // sun-side offset for the soft second layer
        val shx = -dir[0] * 0.7f; val shz = -dir[2] * 0.7f
        val t = world.traffic
        for (i in 0 until t.count) {
            val o = t.get(i)
            if (!o.alive || !Kind.isVehicle(o.kind)) continue
            val ds = (o.s - world.s).toFloat()
            if (ds > 160f || ds < -30f) continue
            locate(world, o.s, o.x, 0f)
            shadowQuad(o.halfW * 1.04f, o.halfL * 1.03f, a, 0f, 0f)
            if (quality.shadows > 1) shadowQuad(o.halfW * 1.04f, o.halfL * 1.03f, a * 0.5f, shx, shz)
        }
        locate(world, world.s, world.x, 0f)
        shadowQuad(Cfg.PLAYER_HALF_W * 1.05f, Cfg.PLAYER_HALF_L * 1.03f, a, 0f, 0f)
        if (quality.shadows > 1) shadowQuad(Cfg.PLAYER_HALF_W * 1.05f, Cfg.PLAYER_HALF_L * 1.03f, a * 0.5f, shx, shz)
    }

    private fun glowAt(lat: Float, up: Float, fwd: Float, rad: Float, r: Float, g: Float, b: Float, a: Float) {
        val x = ox + cosH * lat + sinH * fwd
        val y = oy + up + slope * fwd
        val z = oz + sinH * lat - cosH * fwd
        batch.glow(x, y, z, rad, rad, basis[0], basis[1], basis[2], basis[3], basis[4], basis[5], r, g, b, a)
    }

    private fun drawGlows(world: World) {
        val g = glowK
        val blink = (clock * 2.4f) % 1f < 0.5f
        val t = world.traffic
        for (i in 0 until t.count) {
            val o = t.get(i)
            if (!o.alive) continue
            val ds = (o.s - world.s).toFloat()
            if (ds > 260f || ds < -20f) continue
            locate(world, o.s, o.x, 0f)
            if (Kind.isVehicle(o.kind)) {
                val lx = if (o.kind == Kind.TRUCK) 0.9f else if (o.kind == Kind.VAN) 0.74f else 0.62f
                val ly = if (o.kind == Kind.CAR) 0.68f else 0.84f
                if (o.hazard) {
                    if (blink) {
                        glowAt(-lx, ly, -o.halfL, 0.75f, 1f, 0.6f, 0.05f, 0.85f)
                        glowAt(lx, ly, -o.halfL, 0.75f, 1f, 0.6f, 0.05f, 0.85f)
                    }
                } else if (g > 0.03f) {
                    glowAt(-lx, ly, -o.halfL, 0.6f, 1f, 0.12f, 0.06f, 0.6f * g)
                    glowAt(lx, ly, -o.halfL, 0.6f, 1f, 0.12f, 0.06f, 0.6f * g)
                }
            } else if (o.kind == Kind.BARRIER) {
                if (blink || g < 0.2f) {
                    val a = if (blink) 0.3f + 0.5f * g else 0.15f
                    glowAt(-1.2f, 1.1f, 0f, 0.45f, 1f, 0.65f, 0.05f, a)
                    glowAt(1.2f, 1.1f, 0f, 0.45f, 1f, 0.65f, 0.05f, a)
                }
            } else if (o.kind == Kind.SIGN) {
                glowAt(0f, 2.1f, -0.1f, 1.3f, 1f, 0.65f, 0.05f, if (blink) 0.45f else 0.15f)
            }
        }
        for (p in world.pickups) {
            if (!p.active) continue
            val ds = (p.s - world.s).toFloat()
            if (ds > 200f || ds < -5f) continue
            locate(world, p.s, p.x, 0f)
            if (p.kind == Pickup.COIN) glowAt(0f, 1f, 0f, 1.0f, 1f, 0.8f, 0.2f, 0.22f + 0.2f * g)
            else glowAt(0f, 1.2f, 0f, 1.7f, 0.3f, 0.7f, 1f, 0.5f)
        }
        // street lamps
        if (world.env.night > 0.05f || world.env.dusk > 0.3f) {
            val a = minOf(1f, world.env.night + 0.5f * world.env.dusk)
            for (c in chunks) {
                if (c.generation != world.road.generation) continue
                for (l in 0 until c.lampCount) {
                    val x = (c.originX - camX).toFloat() + c.lamps[l * 3]
                    val y = (c.lamps[l * 3 + 1] - camY).toFloat()
                    val z = (c.originZ - camZ).toFloat() + c.lamps[l * 3 + 2]
                    batch.glow(x, y, z, 1.9f, 1.9f, basis[0], basis[1], basis[2], basis[3], basis[4], basis[5], 1f, 0.9f, 0.6f, 0.6f * a)
                }
            }
        }
        // player lights: tail (brighter when braking), headlights at night, nitro flare
        locate(world, world.s, world.x, 0f)
        if (world.state != World.ATTRACT || g > 0f) {
            val yaw = world.yaw * 1.6f
            val s = sin(yaw); val c = cos(yaw)
            val sh = sinH * c + cosH * s; val ch = cosH * c - sinH * s
            sinH = sh; cosH = ch
            val tail = (if (world.braking) 0.75f else 0.12f) + 0.5f * g
            glowAt(-0.6f, 0.62f, -2.1f, 0.7f, 1f, 0.1f, 0.05f, tail)
            glowAt(0.6f, 0.62f, -2.1f, 0.7f, 1f, 0.1f, 0.05f, tail)
            if (g > 0.03f) {
                glowAt(-0.64f, 0.56f, 2.15f, 0.8f, 1f, 0.95f, 0.75f, 0.8f * g)
                glowAt(0.64f, 0.56f, 2.15f, 0.8f, 1f, 0.95f, 0.75f, 0.8f * g)
            }
            if (boostSmooth > 0.05f) {
                glowAt(-0.45f, 0.3f, -2.3f, 0.9f, 0.3f, 0.7f, 1f, 0.9f * boostSmooth)
                glowAt(0.45f, 0.3f, -2.3f, 0.9f, 0.3f, 0.7f, 1f, 0.9f * boostSmooth)
            }
        }
    }

    private fun drawParticles(world: World, additive: Boolean) {
        val p = world.particles
        for (i in 0 until p.limit) {
            if (p.life[i] <= 0f || p.additive[i] != additive) continue
            locate(world, p.s[i], p.x[i], p.y[i])
            val k = p.life[i] / p.maxLife[i]
            val sz = p.size[i] * (if (additive) 0.35f + 0.45f * k else 1.6f - 0.6f * k)
            val a = if (additive) k else 0.55f * k
            val ux = basis[0] * sz; val uy = basis[1] * sz; val uz = basis[2] * sz
            val vx = basis[3] * sz; val vy = basis[4] * sz; val vz = basis[5] * sz
            batch.quad(
                ox - ux - vx, oy - uy - vy, oz - uz - vz, ox + ux - vx, oy + uy - vy, oz + uz - vz,
                ox + ux + vx, oy + uy + vy, oz + uz + vz, ox - ux + vx, oy - uy + vy, oz - uz + vz,
                p.r[i], p.g[i], p.b[i], a,
            )
        }
    }

    private fun drawRain(world: World, dt: Float) {
        val rain = world.env.rain
        val n = minOf(MAX_RAIN, (quality.rainDrops * rain).toInt())
        val vz = world.speed * 0.75f
        val a = 0.4f * rain
        for (i in 0 until n) {
            rainY[i] -= 15f * dt
            rainZ[i] += vz * dt
            rainX[i] += 1.2f * dt
            if (rainY[i] < -5f) rainY[i] += 11f
            if (rainX[i] > 11f) rainX[i] -= 22f
            if (rainZ[i] > -0.6f) { rainZ[i] -= 29f; rainX[i] = fx.range(-11f, 11f) }
            val x = rainX[i]; val y = rainY[i]; val z = rainZ[i]
            val k = 0.028f
            val x2 = x + 1.2f * k; val y2 = y - 15f * k; val z2 = z + vz * k
            val w = 0.012f + 0.0015f * -z
            batch.quad(x - w, y, z, x + w, y, z, x2 + w, y2, z2, x2 - w, y2, z2, 0.8f, 0.86f, 0.95f, a)
        }
    }

    private fun drawSpeedLines(world: World) {
        if (world.state == World.ATTRACT) return
        var k = (world.speed - 38f) / 30f
        if (k < 0f) k = 0f
        if (k > 1f) k = 1f
        k = k * 0.55f + boostSmooth * 0.75f
        if (world.state != World.RUNNING) k *= 0.3f
        if (k < 0.03f) return
        if (k > 1f) k = 1f
        val n = (quality.speedLines * k).toInt()
        val aspect = width.toFloat() / height
        fx.setSeed((frame / 2) * 7919L)
        for (i in 0 until n) {
            val ang = fx.range(0f, 6.2831855f)
            val r0 = fx.range(0.6f, 1.05f)
            val len = fx.range(0.1f, 0.3f) * (0.5f + k)
            val c = cos(ang); val s = sin(ang)
            val w = 0.007f + 0.009f * k
            // radial in normalised device coordinates, so streaks hug all four screen edges
            val x0 = c * r0; val y0 = s * r0
            val x1 = c * (r0 + len); val y1 = s * (r0 + len)
            val px = -s * w; val py = c * w * aspect
            batch.reserve(3)
            val a = 0.22f + 0.4f * k
            batch.v(x0, y0, 0f, 1f, 1f, 1f, 0f)
            batch.v(x1 - px, y1 - py, 0f, 1f, 1f, 1f, a)
            batch.v(x1 + px, y1 + py, 0f, 1f, 1f, 1f, a)
        }
    }

    companion object {
        const val MAX_CHUNKS = 20
        private const val STARS = 110
        private const val RIDGE = 40
        private const val CLOUDS = 11
        private const val MAX_RAIN = 200
        private const val DOME = 60f

        private val SKY_EL = floatArrayOf(-0.5f, 0f, 0.13f, 0.5f, 1.5707964f)
        private val SKY_T = floatArrayOf(0f, 0f, 0.28f, 0.8f, 1f)

        // per phase: CLEAR, DUSK, NIGHT, RAIN
        private val ZEN = floatArrayOf(0.22f, 0.50f, 0.92f, 0.17f, 0.20f, 0.46f, 0.012f, 0.02f, 0.07f, 0.40f, 0.45f, 0.50f)
        private val HOR = floatArrayOf(0.74f, 0.86f, 0.97f, 0.99f, 0.60f, 0.36f, 0.05f, 0.08f, 0.17f, 0.63f, 0.67f, 0.70f)
        private val SUN = floatArrayOf(1.0f, 0.95f, 0.84f, 0.95f, 0.50f, 0.28f, 0.16f, 0.20f, 0.34f, 0.42f, 0.44f, 0.47f)
        private val AMB = floatArrayOf(0.46f, 0.52f, 0.62f, 0.42f, 0.36f, 0.44f, 0.11f, 0.13f, 0.22f, 0.50f, 0.53f, 0.57f)
        private val DIR = floatArrayOf(-0.45f, 0.80f, 0.40f, 0.22f, 0.16f, -0.96f, -0.30f, 0.55f, -0.78f, -0.3f, 0.85f, 0.3f)
        private val FOGF = floatArrayOf(0.42f, 0.40f, 0.30f, 0.16f)

        private const val LIT_VS = """
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec3 aColor;
attribute float aMat;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform vec3 uSunDir;
uniform vec3 uSunCol;
uniform vec3 uAmbient;
uniform vec3 uTint;
uniform vec4 uParams;
uniform vec3 uHeadPos;
uniform vec3 uHeadDir;
varying vec3 vCol;
varying float vFog;
void main() {
  vec4 wp = uModel * vec4(aPos, 1.0);
  vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);
  float isEm = step(0.5, aMat) * step(aMat, 1.5);
  float isPaint = step(1.5, aMat) * step(aMat, 2.5);
  float isLamp = step(2.5, aMat);
  vec3 alb = aColor * mix(vec3(1.0), uTint, isPaint);
  alb = mix(alb, vec3(0.17, 0.21, 0.28), isLamp);
  float ndl = max(dot(n, uSunDir), 0.0);
  vec3 lit = alb * (uAmbient * (0.82 + 0.18 * n.y) + uSunCol * ndl);
  vec3 d = wp.xyz - uHeadPos;
  float along = dot(d, uHeadDir);
  float lat = length(d - uHeadDir * along);
  float h = clamp(along * 0.35, 0.0, 1.0) * clamp(1.0 - along / 62.0, 0.0, 1.0)
      * clamp(1.0 - lat / (1.6 + along * 0.2), 0.0, 1.0);
  lit += alb * vec3(1.0, 0.94, 0.78) * (h * uParams.w);
  vec3 col = mix(lit, aColor, isEm);
  col = mix(col, aColor, isLamp * uParams.z);
  float f = clamp((length(wp.xyz) - uParams.x) * uParams.y, 0.0, 1.0);
  f = f * f * (3.0 - 2.0 * f);
  vFog = f * (1.0 - 0.35 * max(isEm, isLamp * uParams.z));
  vCol = col;
  gl_Position = uMVP * vec4(aPos, 1.0);
}
"""
        private const val LIT_FS = """
uniform vec3 uFogCol;
uniform float uAlpha;
varying vec3 vCol;
varying float vFog;
void main() {
  gl_FragColor = vec4(mix(vCol, uFogCol, vFog), uAlpha);
}
"""
        private const val UNLIT_VS = """
attribute vec3 aPos;
attribute vec4 aColor;
uniform mat4 uMVP;
varying vec4 vCol;
void main() {
  vCol = aColor;
  gl_Position = uMVP * vec4(aPos, 1.0);
}
"""
        private const val UNLIT_FS = """
varying vec4 vCol;
void main() {
  gl_FragColor = vCol;
}
"""
    }
}
