package com.endlessdrive.render

import com.endlessdrive.core.GameConfig
import com.endlessdrive.core.GameSession
import com.endlessdrive.core.Kind
import com.endlessdrive.core.Mode
import com.endlessdrive.core.PickupKind
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the whole 3D scene for a [GameSession]. Rendering is camera-relative (the eye sits at the
 * origin; world positions are kept in doubles and subtracted before converting to float), which
 * keeps precision at any distance travelled. render() performs no allocations.
 */
class SceneRenderer(roadSeed: Long = 1234L) {
    val meshes = Meshes()
    val road = RoadGeometry(roadSeed)
    val chunks = RoadChunks(meshes)
    val env = Environment()
    private val fx = FxBatch()

    var quality: Quality = Quality.of(Quality.HIGH)
        set(v) {
            field = v
            chunks.setDensity(v.sceneryDensity)
        }

    private lateinit var lit: Program
    private lateinit var sky: Program
    private lateinit var fxp: Program
    private var uLitVP = 0; private var uLitModel = 0; private var uLitTint = 0; private var uLitSun = 0
    private var uLitSunColor = 0; private var uLitAmbient = 0; private var uLitFog = 0; private var uLitFogD = 0
    private var uLitNight = 0; private var uLitWet = 0
    private var uSkyVP = 0; private var uSkyZen = 0; private var uSkyHor = 0; private var uSkyFog = 0
    private var uSkySun = 0; private var uSkySunC = 0; private var uSkyNight = 0
    private var uFxVP = 0; private var uFxScale = 0; private var uFxRound = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val ident = FloatArray(16).also { Mat4.identity(it) }
    private val pose = RoadPose()
    private val pose2 = RoadPose()

    var width = 1; private set
    var height = 1; private set
    private var camLat = 0f
    private var fov = 60f
    private var time = 0f
    var eyeX = 0.0; private set
    var eyeY = 0.0; private set
    var eyeZ = 0.0; private set
    private var initialized = false

    // rain drops (camera-relative)
    private val rainX = FloatArray(600)
    private val rainY = FloatArray(600)
    private val rainZ = FloatArray(600)
    private val rnd = java.util.Random(5)

    // fx segments
    private var segShadow = 0; private var nShadow = 0
    private var segParticles = 0; private var nParticles = 0
    private var segGlow = 0; private var nGlow = 0
    private var segBeam = 0; private var nBeam = 0
    private var segRain = 0; private var nRain = 0
    private var segSpeed = 0; private var nSpeed = 0

    /** (Re)creates every GL resource. Call from onSurfaceCreated (also after EGL context loss). */
    fun onSurfaceCreated(gl: GL) {
        lit = Program.build(gl, Shaders.LIT_VS, Shaders.LIT_FS, arrayOf("aPos", "aNormal", "aColor"))
        sky = Program.build(gl, Shaders.SKY_VS, Shaders.SKY_FS, arrayOf("aPos"))
        fxp = Program.build(gl, Shaders.FX_VS, Shaders.FX_FS, arrayOf("aPos", "aColor", "aSize"))
        uLitVP = lit.loc(gl, "uViewProj"); uLitModel = lit.loc(gl, "uModel"); uLitTint = lit.loc(gl, "uTint")
        uLitSun = lit.loc(gl, "uSunDir"); uLitSunColor = lit.loc(gl, "uSunColor"); uLitAmbient = lit.loc(gl, "uAmbient")
        uLitFog = lit.loc(gl, "uFogColor"); uLitFogD = lit.loc(gl, "uFogDensity"); uLitNight = lit.loc(gl, "uNight")
        uLitWet = lit.loc(gl, "uWet")
        uSkyVP = sky.loc(gl, "uViewProj"); uSkyZen = sky.loc(gl, "uZenith"); uSkyHor = sky.loc(gl, "uHorizon")
        uSkyFog = sky.loc(gl, "uFogColor"); uSkySun = sky.loc(gl, "uSunDir"); uSkySunC = sky.loc(gl, "uSunColor")
        uSkyNight = sky.loc(gl, "uNight")
        uFxVP = fxp.loc(gl, "uViewProj"); uFxScale = fxp.loc(gl, "uPointScale"); uFxRound = fxp.loc(gl, "uRound")
        meshes.upload(gl)
        chunks.init(gl)
        fx.init(gl)
        for (i in rainX.indices) respawnDrop(i, true)
        initialized = true
    }

    fun onSurfaceChanged(gl: GL, w: Int, h: Int) {
        width = maxOf(1, w)
        height = maxOf(1, h)
        gl.viewport(0, 0, width, height)
    }

    fun render(gl: GL, session: GameSession, dtIn: Float) {
        if (!initialized) return
        val dt = dtIn.coerceIn(0f, 0.05f)
        time += dt
        val world = session.world
        val p = world.player
        val q = quality
        env.update(session.envTime)
        road.ensure(p.s - 80.0, p.s + q.drawDistance + 80.0)
        chunks.update(gl, road, p.s - 30.0, p.s + q.drawDistance + 10.0)

        // ---------------- camera ----------------
        val crashed = session.mode == Mode.CRASHING || session.mode == Mode.GAME_OVER
        val sn = p.speedNorm()
        camLat += (p.x * 0.85f - camLat) * (1f - exp(-dt * 6f))
        val attract = session.mode == Mode.ATTRACT
        val camBack = if (attract) 9.5 else 7.2 + sn * 0.8
        val camUp = if (attract) 3.4f else 2.55f
        road.pose(p.s - camBack, pose)
        road.pose(p.s + 14.0, pose2)
        var lat = camLat + (if (attract) sin(time * 0.25f) * 2.2f else 0f)
        var up = camUp
        val sh = session.shake
        if (sh > 0f) {
            lat += sin(time * 61f) * sh * 0.35f
            up += cos(time * 53f) * sh * 0.25f
        }
        eyeX = pose.x + pose.rx * lat
        eyeY = pose.y + up
        eyeZ = pose.z + pose.rz * lat
        val tx = (pose2.x + pose2.rx * p.x * 0.6f - eyeX).toFloat()
        val ty = (pose2.y + 0.9 - eyeY).toFloat()
        val tz = (pose2.z + pose2.rz * p.x * 0.6f - eyeZ).toFloat()
        Mat4.lookAt(view, 0f, 0f, 0f, tx, ty, tz)
        val targetFov = 58f + 16f * sn + (if (p.boosting && !crashed) 7f else 0f)
        fov += (targetFov - fov) * (1f - exp(-dt * 3f))
        Mat4.perspective(proj, fov * 0.017453292f, width.toFloat() / height, 0.3f, 1800f)
        Mat4.multiply(vp, proj, view)

        // ---------------- clear + sky ----------------
        gl.clearColor(env.fog[0], env.fog[1], env.fog[2], 1f)
        gl.clear(GLC.COLOR_BUFFER_BIT or GLC.DEPTH_BUFFER_BIT)
        gl.disable(GLC.CULL_FACE)
        gl.disable(GLC.BLEND)
        gl.disable(GLC.DEPTH_TEST)
        gl.depthMask(false)
        gl.useProgram(sky.id)
        gl.uniformMatrix4fv(uSkyVP, vp)
        gl.uniform3f(uSkyZen, env.zenith[0], env.zenith[1], env.zenith[2])
        gl.uniform3f(uSkyHor, env.horizon[0], env.horizon[1], env.horizon[2])
        gl.uniform3f(uSkyFog, env.fog[0], env.fog[1], env.fog[2])
        gl.uniform3f(uSkySun, env.sunDir[0], env.sunDir[1], env.sunDir[2])
        gl.uniform3f(uSkySunC, env.sunColor[0], env.sunColor[1], env.sunColor[2])
        gl.uniform1f(uSkyNight, env.night)
        meshes.gSky.draw(gl)
        gl.enable(GLC.DEPTH_TEST)
        gl.depthMask(true)

        // ---------------- lit geometry ----------------
        gl.useProgram(lit.id)
        gl.uniformMatrix4fv(uLitVP, vp)
        gl.uniform3f(uLitSun, env.sunDir[0], env.sunDir[1], env.sunDir[2])
        gl.uniform3f(uLitSunColor, env.sunColor[0], env.sunColor[1], env.sunColor[2])
        gl.uniform3f(uLitAmbient, env.ambient[0], env.ambient[1], env.ambient[2])
        gl.uniform3f(uLitFog, env.fog[0], env.fog[1], env.fog[2])
        gl.uniform1f(uLitNight, env.night)
        gl.uniform1f(uLitWet, env.rain)
        gl.uniform4f(uLitTint, 1f, 1f, 1f, 1f)
        // distant mountains: fixed in world orientation, infinitely far -> pure parallax backdrop
        gl.uniform1f(uLitFogD, 0.0006f)
        road.pose(p.s, pose)
        Mat4.model(model, 0f, 0f, 1f, 0f, (pose.y - eyeY).toFloat() - 30f, 0f, 1300f, 900f, 1300f)
        gl.uniformMatrix4fv(uLitModel, model)
        meshes.gMountains.draw(gl)

        val fogD = 1.85f / q.drawDistance * env.fogScale
        gl.uniform1f(uLitFogD, fogD)
        for (i in 0 until chunks.slotCount()) {
            if (chunks.slotChunk(i) < 0) continue
            Mat4.translation(
                model, (chunks.originX[i] - eyeX).toFloat(), (chunks.originY[i] - eyeY).toFloat(),
                (chunks.originZ[i] - eyeZ).toFloat(),
            )
            gl.uniformMatrix4fv(uLitModel, model)
            chunks.slotMesh(i).draw(gl)
        }

        // traffic
        val maxD = q.drawDistance + 20.0
        for (c in world.traffic.cars) {
            if (!c.active || c.s < p.s - 20.0 || c.s > p.s + maxD) continue
            when (c.kind) {
                Kind.CAR, Kind.STOPPED_CAR, Kind.SHOULDER_CAR -> drawOnRoad(gl, meshes.car(c.variant), c.s, c.x, 0f, 0f)
                Kind.TRUCK -> drawOnRoad(gl, meshes.gTrucks[c.variant % meshes.gTrucks.size], c.s, c.x, 0f, 0f)
                Kind.BARRIER -> drawOnRoad(gl, meshes.gBarrier, c.s, c.x, 0f, 0f, c.halfW / 1.6f)
                Kind.CONES -> {
                    val inner = if (c.lane == 0) 1f else -1f
                    for (k in 0 until 5) {
                        val t = k / 4f
                        drawOnRoad(gl, meshes.gCone, c.s - c.halfL + t * 2 * c.halfL, c.x + inner * (-c.halfW + 0.2f + t * (2 * c.halfW - 0.4f)) * 0.9f, 0f, 0f)
                    }
                }
                Kind.ROADWORKS -> drawRoadworks(gl, c.s, c.x, c.halfW, c.halfL, c.lane)
            }
        }
        // pickups
        for (pk in world.pickups) {
            if (!pk.active || pk.s > p.s + maxD || pk.s < p.s - 10.0) continue
            if (pk.kind == PickupKind.COIN) {
                drawOnRoad(gl, meshes.gCoin, pk.s, pk.x, 0.9f + 0.15f * sin(time * 3f + pk.s.toFloat()), time * 4f)
            } else {
                drawOnRoad(gl, meshes.gShield, pk.s, pk.x, 1.1f + 0.2f * sin(time * 2.5f), time * 2f)
            }
        }
        // player
        val blink = world.invulnTimer > 0f && world.shieldTimer <= 0f && ((time * 10f).toInt() and 1) == 1
        if (!blink) {
            val spin = if (crashed) session.crashSpin * 0.9f * (1f - exp(-session.crashTimer * 4f)) else 0f
            drawOnRoad(gl, meshes.gPlayer, p.s, p.x, 0f, p.yaw + spin)
        }

        // ---------------- effects ----------------
        buildFx(session, dt)
        fx.upload(gl)
        gl.useProgram(fxp.id)
        gl.uniformMatrix4fv(uFxVP, vp)
        gl.uniform1f(uFxScale, height * proj[5] * 0.5f)
        gl.depthMask(false)
        gl.enable(GLC.BLEND)
        gl.blendFunc(GLC.SRC_ALPHA, GLC.ONE_MINUS_SRC_ALPHA)
        gl.uniform1i(uFxRound, 0)
        fx.draw(gl, GLC.TRIANGLES, segShadow, nShadow)
        gl.uniform1i(uFxRound, 1)
        fx.draw(gl, GLC.POINTS, segParticles, nParticles)
        gl.uniform1i(uFxRound, 0)
        gl.lineWidth(1.5f)
        fx.draw(gl, GLC.LINES, segRain, nRain)
        gl.blendFunc(GLC.SRC_ALPHA, GLC.ONE)
        fx.draw(gl, GLC.TRIANGLES, segBeam, nBeam)
        gl.uniform1i(uFxRound, 1)
        fx.draw(gl, GLC.POINTS, segGlow, nGlow)
        gl.disable(GLC.DEPTH_TEST)
        gl.uniform1i(uFxRound, 0)
        gl.uniformMatrix4fv(uFxVP, ident)
        gl.lineWidth(2f)
        fx.draw(gl, GLC.LINES, segSpeed, nSpeed)
        gl.enable(GLC.DEPTH_TEST)
        gl.depthMask(true)
        gl.disable(GLC.BLEND)
    }

    private fun drawRoadworks(gl: GL, s: Double, x: Float, halfW: Float, halfL: Float, lane: Int) {
        val inner = if (lane == 0) 1f else -1f
        drawOnRoad(gl, meshes.gPatch, s, x, 0f, 0f, halfW - 0.35f, 1f, halfL - 1f)
        var d = -halfL
        while (d <= halfL) {
            drawOnRoad(gl, meshes.gCone, s + d, x + inner * (halfW - 0.15f), 0f, 0f)
            d += 5f
        }
        drawOnRoad(gl, meshes.gBarrier, s - halfL, x, 0f, 0f, halfW / 1.6f)
        drawOnRoad(gl, meshes.gBarrier, s + halfL, x, 0f, 0f, halfW / 1.6f)
        drawOnRoad(gl, meshes.gWorkSign, s - halfL - 30.0, -inner * 7.0f, 0f, 3.1416f)
    }

    /** Draws [mesh] at road coordinates (s, x) with height and yaw (relative to the road). */
    private fun drawOnRoad(
        gl: GL, mesh: Mesh, s: Double, x: Float, yOff: Float, yaw: Float,
        sx: Float = 1f, sy: Float = 1f, sz: Float = 1f,
    ) {
        road.pose(s, pose)
        val h = pose.heading - yaw
        val c = 1f / sqrt(1f + pose.grade * pose.grade)
        Mat4.model(
            model, sin(h) * c, pose.grade * c, cos(h) * c,
            (pose.x + pose.rx * x - eyeX).toFloat(), (pose.y - eyeY).toFloat() + yOff, (pose.z + pose.rz * x - eyeZ).toFloat(),
            sx, sy, sz,
        )
        gl.uniformMatrix4fv(uLitModel, model)
        mesh.draw(gl)
    }

    // ------------------------------------------------------------------ effects

    private fun rel(s: Double, x: Float, y: Float, out: FloatArray) {
        road.pose(s, pose2)
        out[0] = (pose2.x + pose2.rx * x - eyeX).toFloat()
        out[1] = (pose2.y + y - eyeY).toFloat()
        out[2] = (pose2.z + pose2.rz * x - eyeZ).toFloat()
    }

    private val tmp = FloatArray(3)

    private fun glow(s: Double, x: Float, y: Float, argb: Int, alpha: Float, size: Float) {
        if (alpha <= 0.01f) return
        rel(s, x, y, tmp)
        fx.v(tmp[0], tmp[1], tmp[2], argb, alpha, size)
    }

    private fun buildFx(session: GameSession, dt: Float) {
        val world = session.world
        val p = world.player
        val q = quality
        val night = env.night
        fx.begin()

        // blob shadows
        segShadow = fx.count
        if (q.shadows) {
            for (c in world.traffic.cars) {
                if (!c.active || c.s > p.s + 120.0 || c.s < p.s - 15.0) continue
                if (c.kind == Kind.CONES || c.kind == Kind.ROADWORKS) continue
                shadowQuad(c.s, c.x, c.halfW + 0.2f, c.halfL + 0.25f)
            }
            shadowQuad(p.s, p.x, GameConfig.CAR_HALF_W + 0.2f, GameConfig.CAR_HALF_L + 0.25f)
        }
        nShadow = fx.count - segShadow

        // particles
        segParticles = fx.count
        val ps = session.fx
        for (i in 0 until ps.capacity) {
            if (ps.life[i] <= 0f) continue
            rel(ps.s[i], ps.x[i], ps.y[i], tmp)
            fx.v(tmp[0], tmp[1], tmp[2], ps.color[i], ps.alpha(i), ps.size[i])
        }
        // shield bubble around the player
        if (world.shieldTimer > 0f && (world.shieldTimer > 1.5f || ((time * 8f).toInt() and 1) == 0)) {
            for (k in 0 until 28) {
                val a = k * 0.2244f + time * 1.5f
                val b = sin(k * 1.7f) * 1.2f
                glowTo(p.s + cos(a) * 2.6 * cos(b), p.x + sin(a) * 1.5f * cos(b), 0.8f + sin(b) * 0.9f, 0xFF4DD0E1.toInt(), 0.35f, 0.5f)
            }
        }
        nParticles = fx.count - segParticles

        // glows (lights)
        segGlow = fx.count
        if (q.glows || night > 0.3f) {
            val base = 0.15f + 0.85f * night
            for (c in world.traffic.cars) {
                if (!c.active || c.s > p.s + q.drawDistance || c.s < p.s - 10.0) continue
                when (c.kind) {
                    Kind.CAR, Kind.TRUCK, Kind.STOPPED_CAR, Kind.SHOULDER_CAR -> {
                        val hw = c.halfW * 0.66f
                        val a = if (c.braking) 1f else base
                        val y = if (c.kind == Kind.TRUCK) 0.9f else 0.7f
                        glow(c.s - c.halfL, c.x - hw, y, 0xFFFF2020.toInt(), a, if (c.braking) 0.9f else 0.6f)
                        glow(c.s - c.halfL, c.x + hw, y, 0xFFFF2020.toInt(), a, if (c.braking) 0.9f else 0.6f)
                        if (c.kind == Kind.STOPPED_CAR || c.kind == Kind.SHOULDER_CAR) {
                            val on = if (((time * 2.5f).toInt() and 1) == 0) 1f else 0.1f
                            glow(c.s - c.halfL, c.x - hw - 0.2f, y + 0.05f, 0xFFFFA000.toInt(), on, 1.1f)
                            glow(c.s - c.halfL, c.x + hw + 0.2f, y + 0.05f, 0xFFFFA000.toInt(), on, 1.1f)
                        }
                    }
                    Kind.BARRIER, Kind.ROADWORKS -> {
                        val blink = 0.4f + 0.6f * (0.5f + 0.5f * sin(time * 8f + c.s.toFloat()))
                        val back = c.s - c.halfL
                        glow(back, c.x - c.halfW * 0.84f, 1.05f, 0xFFFFB300.toInt(), blink * (0.4f + 0.6f * night), 1.2f)
                        glow(back, c.x + c.halfW * 0.84f, 1.05f, 0xFFFFB300.toInt(), blink * (0.4f + 0.6f * night), 1.2f)
                    }
                }
            }
            // player tail lights + boost flames
            val hl = GameConfig.CAR_HALF_L
            glow(p.s - hl, p.x - 0.63f, 0.6f, 0xFFFF2020.toInt(), if (p.braking) 1f else base, if (p.braking) 0.9f else 0.55f)
            glow(p.s - hl, p.x + 0.63f, 0.6f, 0xFFFF2020.toInt(), if (p.braking) 1f else base, if (p.braking) 0.9f else 0.55f)
            if (p.boosting && session.mode == Mode.PLAYING) {
                val f = 0.7f + 0.3f * sin(time * 40f)
                glow(p.s - hl - 0.25, p.x - 0.35f, 0.3f, 0xFF40C4FF.toInt(), f, 0.9f)
                glow(p.s - hl - 0.25, p.x + 0.35f, 0.3f, 0xFF40C4FF.toInt(), f, 0.9f)
            }
            if (night > 0.05f) {
                for (i in 0 until chunks.slotCount()) {
                    if (chunks.slotChunk(i) < 0) continue
                    val arr = chunks.lamps[i]
                    for (k in 0 until chunks.lampCount[i]) {
                        fx.v(
                            (chunks.originX[i] - eyeX).toFloat() + arr[k * 3], (chunks.originY[i] - eyeY).toFloat() + arr[k * 3 + 1],
                            (chunks.originZ[i] - eyeZ).toFloat() + arr[k * 3 + 2], 0xFFFFD180.toInt(), night * 0.8f, 3.5f,
                        )
                    }
                }
            }
        }
        nGlow = fx.count - segGlow

        // player headlight beam on the road (night/dusk/rain)
        segBeam = fx.count
        if (night > 0.05f && session.mode != Mode.GAME_OVER) {
            val a = night * 0.45f
            beamQuad(p.s + 2.2, p.x, 0.8f, p.s + 14.0, p.x + p.yaw * 12f, 2.6f, a, a * 0.5f)
            beamQuad(p.s + 14.0, p.x + p.yaw * 12f, 2.6f, p.s + 34.0, p.x + p.yaw * 30f, 4.2f, a * 0.5f, 0f)
        }
        nBeam = fx.count - segBeam

        // rain streaks
        segRain = fx.count
        if (env.rain > 0.02f) {
            val n = (q.rainDrops * env.rain).toInt().coerceAtMost(rainX.size)
            road.pose(p.s, pose2)
            val fwdX = pose2.fx * p.v * dt; val fwdZ = pose2.fz * p.v * dt
            for (i in 0 until n) {
                rainY[i] -= 16f * dt
                rainX[i] -= fwdX; rainZ[i] -= fwdZ
                val d2 = rainX[i] * rainX[i] + rainZ[i] * rainZ[i]
                if (rainY[i] < -3f || d2 > 900f) respawnDrop(i, false)
                val sx = rainX[i]; val sy = rainY[i]; val sz = rainZ[i]
                fx.v(sx, sy, sz, 0x99B0C4D8.toInt(), env.rain, 1f)
                fx.v(sx - pose2.fx * 0.02f * p.v, sy + 0.55f, sz - pose2.fz * 0.02f * p.v, 0x99B0C4D8.toInt(), env.rain * 0.2f, 1f)
            }
        }
        nRain = fx.count - segRain

        // speed lines (NDC space)
        segSpeed = fx.count
        val sn = p.speedNorm()
        val intensity = if (session.mode == Mode.PLAYING) (((sn - 0.45f) / 0.55f).coerceIn(0f, 1f) + if (p.boosting) 0.6f else 0f) else 0f
        if (intensity > 0.01f) {
            for (k in 0 until 26) {
                val ang = k * (6.2831855f / 26f) + 0.13f * sin(k * 7.1f)
                val ph = ((time * (1.6f + (k % 5) * 0.35f) + k * 0.37f) % 1f)
                val r0 = 0.75f + ph * 0.9f
                val r1 = r0 + 0.12f + 0.25f * intensity
                val cx = cos(ang)
                val cy = sin(ang)
                val a = (intensity * 0.5f * (1f - ph)).coerceIn(0f, 0.6f)
                fx.v(cx * r0, cy * r0 * 0.9f, 0f, 0xFFFFFFFF.toInt(), a, 1f)
                fx.v(cx * r1, cy * r1 * 0.9f, 0f, 0xFFFFFFFF.toInt(), 0f, 1f)
            }
        }
        nSpeed = fx.count - segSpeed
    }

    private fun glowTo(s: Double, x: Float, y: Float, argb: Int, a: Float, size: Float) = glow(s, x, y, argb, a, size)

    private val c0 = FloatArray(3)
    private val c1 = FloatArray(3)
    private val c2 = FloatArray(3)
    private val c3 = FloatArray(3)

    private fun shadowQuad(s: Double, x: Float, hw: Float, hl: Float) {
        rel(s - hl, x - hw, 0.04f, c0); rel(s - hl, x + hw, 0.04f, c1)
        rel(s + hl, x + hw, 0.04f, c2); rel(s + hl, x - hw, 0.04f, c3)
        val col = 0x66000000
        fx.v(c0[0], c0[1], c0[2], col); fx.v(c1[0], c1[1], c1[2], col); fx.v(c2[0], c2[1], c2[2], col)
        fx.v(c0[0], c0[1], c0[2], col); fx.v(c2[0], c2[1], c2[2], col); fx.v(c3[0], c3[1], c3[2], col)
    }

    private fun beamQuad(s0: Double, x0: Float, w0: Float, s1: Double, x1: Float, w2: Float, a0: Float, a1: Float) {
        rel(s0, x0 - w0, 0.06f, c0); rel(s0, x0 + w0, 0.06f, c1)
        rel(s1, x1 + w2, 0.06f, c2); rel(s1, x1 - w2, 0.06f, c3)
        val col = 0xFFFFE9B0.toInt()
        fx.v(c0[0], c0[1], c0[2], col, a0); fx.v(c1[0], c1[1], c1[2], col, a0); fx.v(c2[0], c2[1], c2[2], col, a1)
        fx.v(c0[0], c0[1], c0[2], col, a0); fx.v(c2[0], c2[1], c2[2], col, a1); fx.v(c3[0], c3[1], c3[2], col, a1)
    }

    private fun respawnDrop(i: Int, initial: Boolean) {
        val a = rnd.nextFloat() * 6.2831855f
        val r = 2f + rnd.nextFloat() * 26f
        rainX[i] = cos(a) * r
        rainZ[i] = sin(a) * r
        rainY[i] = if (initial) rnd.nextFloat() * 14f - 3f else 9f + rnd.nextFloat() * 4f
    }
}
