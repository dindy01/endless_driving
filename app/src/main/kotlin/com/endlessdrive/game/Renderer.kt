package com.endlessdrive.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.endlessdrive.core.GameConfig
import com.endlessdrive.core.ObstacleKind
import com.endlessdrive.core.PickupKind
import com.endlessdrive.core.World
import com.endlessdrive.core.fx.ParticleSystem
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Per-frame view state handed from GameView to the Renderer (mutable, reused). */
class FrameInfo {
    var camZ = 0f
    var clock = 0f
    var cycle = 0f
    var speed = GameConfig.V_MIN
    var showHud = false
    var showPlayer = true
    var playerX = 0f
    var playerTilt = 0f
    var playerSpin = 0f
    var playerBlink = false
    var shield = 0f
    var shake = 0f
    var flash = 0f
    var combo = 0
    var coins = 0
    var score = 0L
}

/**
 * Procedural top-down renderer using Canvas primitives only (no bitmaps/assets).
 * Everything (paints, paths, shaders, rects, text buffers) is allocated up front;
 * draw() performs no allocations.
 */
class Renderer(context: Context) {
    private val dp = context.resources.displayMetrics.density
    private val sp = dp * context.resources.configuration.fontScale

    var width = 1; private set
    var height = 1; private set
    var ppu = 100f; private set
    var playerY = 0f; private set
    private var insetTop = 0f
    private var insetBottom = 0f
    private var viewUnitsW = VIEW_W

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        color = Color.WHITE
        setShadowLayer(6f * dp, 0f, 2f * dp, 0xAA000000.toInt())
    }
    private val hudSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = 0xCCFFFFFF.toInt()
        setShadowLayer(4f * dp, 0f, 1f * dp, 0xAA000000.toInt())
    }
    private val popPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.ITALIC)
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f * dp, 0f, 2f * dp, 0xCC000000.toInt())
    }
    private val vignette = Paint()
    private val r = RectF()
    private val conePath = Path().apply {
        moveTo(-0.18f, 0f); lineTo(0.18f, 0f); lineTo(0.85f, -2.6f); lineTo(-0.85f, -2.6f); close()
    }
    private val shieldGlyph = Path().apply {
        moveTo(0f, -0.13f); lineTo(0.11f, -0.08f); lineTo(0.1f, 0.03f)
        quadTo(0.07f, 0.11f, 0f, 0.15f); quadTo(-0.07f, 0.11f, -0.1f, 0.03f); lineTo(-0.11f, -0.08f); close()
    }
    private val coneShader = LinearGradient(0f, 0f, 0f, -2.6f, 0x88FFF1C2.toInt(), 0x00FFF1C2, Shader.TileMode.CLAMP)
    private val lampShader = RadialGradient(0f, 0f, 1f, 0x77FFD180, 0x00FFD180, Shader.TileMode.CLAMP)
    private val redShader = RadialGradient(0f, 0f, 1f, 0x99FF2A2A.toInt(), 0x00FF2A2A, Shader.TileMode.CLAMP)
    private val amberShader = RadialGradient(0f, 0f, 1f, 0xAAFFAB00.toInt(), 0x00FFAB00, Shader.TileMode.CLAMP)

    private val numBuf = CharArray(24)
    private val popText = arrayOfNulls<String>(MAX_POPUPS)
    private val popX = FloatArray(MAX_POPUPS)
    private val popZ = FloatArray(MAX_POPUPS)
    private val popLife = FloatArray(MAX_POPUPS)
    private val popColor = IntArray(MAX_POPUPS)
    private val popSize = FloatArray(MAX_POPUPS)
    private var popCursor = 0

    private val lineX = FloatArray(SPEED_LINES)
    private val lineY = FloatArray(SPEED_LINES)
    private val lineV = FloatArray(SPEED_LINES)
    private val lineLen = FloatArray(SPEED_LINES)

    // Day/night palette (resolved every frame into these fields).
    private var cGrass = 0
    private var cGrassDark = 0
    private var cAsphalt = 0
    private var cAsphaltPatch = 0
    private var cMark = 0
    private var cTree = 0
    private var cTreeLight = 0
    private var cGlass = 0
    private var night = 0f

    init {
        val rnd = java.util.Random(11)
        for (i in 0 until SPEED_LINES) {
            val side = if (i % 2 == 0) 0f else 0.8f
            lineX[i] = side + rnd.nextFloat() * 0.2f
            lineY[i] = rnd.nextFloat()
            lineV[i] = 1.4f + rnd.nextFloat() * 1.2f
            lineLen[i] = 0.08f + rnd.nextFloat() * 0.12f
        }
    }

    fun setInsets(top: Float, bottom: Float) {
        insetTop = top
        insetBottom = bottom
        resize(width, height)
    }

    fun resize(w: Int, h: Int) {
        width = maxOf(1, w)
        height = maxOf(1, h)
        ppu = min(width / VIEW_W, height / MIN_VIEW_H)
        viewUnitsW = width / ppu
        playerY = height - insetBottom - PLAYER_FROM_BOTTOM * ppu
        vignette.shader = RadialGradient(
            width / 2f, height / 2f, maxOf(width, height) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, 0x66000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private fun sx(x: Float) = width / 2f + x * ppu
    private fun sy(z: Float, camZ: Float) = playerY - (z - camZ) * ppu

    // ------------------------------------------------------------------ popups

    fun addPopup(text: String, x: Float, zRel: Float, color: Int, sizeSp: Float = 22f) {
        val i = popCursor
        popCursor = (popCursor + 1) % MAX_POPUPS
        popText[i] = text
        popX[i] = x
        popZ[i] = zRel
        popLife[i] = POPUP_LIFE
        popColor[i] = color
        popSize[i] = sizeSp
    }

    fun updatePopups(dt: Float) {
        for (i in 0 until MAX_POPUPS) if (popLife[i] > 0f) popLife[i] -= dt
    }

    fun clearPopups() {
        for (i in 0 until MAX_POPUPS) popLife[i] = 0f
    }

    // ------------------------------------------------------------------ main draw

    fun draw(c: Canvas, world: World, fx: ParticleSystem, f: FrameInfo) {
        resolvePalette(f.cycle)
        val camZ = f.camZ

        c.save()
        if (f.shake > 0f) {
            val a = f.shake * 0.16f * ppu
            c.translate(sin(f.clock * 91f) * a, cos(f.clock * 77f) * a)
        }
        c.drawColor(cGrass)
        drawGroundDetails(c, camZ)
        drawRoad(c, camZ)

        // Ground-level objects first (oil, pickups), then vehicles.
        for (o in world.obstacles) if (o.active && o.kind == ObstacleKind.OIL) drawOil(c, o.x, sy(o.z, camZ), o.variant)
        for (pk in world.pickups) if (pk.active) drawPickup(c, pk.kind, pk.x, sy(pk.z, camZ), f.clock, pk.z)
        for (o in world.obstacles) {
            if (!o.active || o.kind == ObstacleKind.OIL) continue
            val y = sy(o.z, camZ)
            if (y < -3f * ppu || y > height + 3f * ppu) continue
            when (o.kind) {
                ObstacleKind.SLOW_CAR -> drawCar(c, o.x, y, ObstacleKind.HALF_W[o.kind], ObstacleKind.HALF_L[o.kind],
                    CAR_COLORS[o.variant % CAR_COLORS.size], 0f, false)
                ObstacleKind.TRUCK -> drawTruck(c, o.x, y, o.variant)
                ObstacleKind.BARRIER -> drawBarrier(c, o.x, y)
            }
        }
        if (f.showPlayer) {
            val visible = !f.playerBlink || ((f.clock * 12f).toInt() and 1) == 0
            if (visible) drawCar(c, f.playerX, playerY, GameConfig.PLAYER_HALF_W, GameConfig.PLAYER_HALF_L,
                PLAYER_COLOR, f.playerTilt + f.playerSpin, true)
            if (f.shield > 0f) drawShieldBubble(c, f.playerX, f.shield, f.clock)
        }
        drawTreesAndLamps(c, camZ)
        drawParticles(c, fx, camZ)
        if (night > 0.01f) drawNight(c, world, f)
        drawClouds(c, camZ)
        drawSpeedLines(c, f)
        c.restore()

        if (f.flash > 0f) c.drawColor(Color.argb((f.flash * 180).toInt().coerceIn(0, 255), 255, 255, 255))
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
        drawPopups(c)
        if (f.showHud) drawHud(c, f)
    }

    private fun resolvePalette(cycle: Float) {
        val ph = cycle % DAY_NIGHT_PERIOD
        val half = DAY_NIGHT_PERIOD / 2f
        val raw = when {
            ph < half - TRANSITION -> 0f
            ph < half -> (ph - (half - TRANSITION)) / TRANSITION
            ph < DAY_NIGHT_PERIOD - TRANSITION -> 1f
            else -> 1f - (ph - (DAY_NIGHT_PERIOD - TRANSITION)) / TRANSITION
        }
        night = raw * raw * (3f - 2f * raw)
        cGrass = lerpColor(0xFF5DAA4E.toInt(), 0xFF1C3A2B.toInt(), night)
        cGrassDark = lerpColor(0xFF3F8C3B.toInt(), 0xFF142B20.toInt(), night)
        cAsphalt = lerpColor(0xFF3D4149.toInt(), 0xFF1F2229.toInt(), night)
        cAsphaltPatch = lerpColor(0xFF353940.toInt(), 0xFF1A1D23.toInt(), night)
        cMark = lerpColor(0xFFF4F4F4.toInt(), 0xFFB9BEC6.toInt(), night)
        cTree = lerpColor(0xFF2E7D32.toInt(), 0xFF10301A.toInt(), night)
        cTreeLight = lerpColor(0xFF4CAF50.toInt(), 0xFF1B4426.toInt(), night)
        cGlass = lerpColor(0xFF9ED6F2.toInt(), 0xFF2C4A5E.toInt(), night)
    }

    // ------------------------------------------------------------------ environment

    private fun drawRoad(c: Canvas, camZ: Float) {
        val half = GameConfig.LANES * GameConfig.LANE_W / 2f
        p.color = cAsphalt
        c.drawRect(sx(-half), 0f, sx(half), height.toFloat(), p)

        // asphalt patches (tile-hashed so they scroll with the road)
        val zMin = camZ - (height - playerY) / ppu - 2f
        val zMax = camZ + playerY / ppu + 2f
        p.color = cAsphaltPatch
        var n = floor(zMin / 2.3f).toInt()
        while (n * 2.3f < zMax) {
            val h = hash(n, 17)
            if (h % 3 != 0) {
                val x = -half + 0.3f + frac(h, 3) * (2 * half - 0.6f)
                val y = sy(n * 2.3f, camZ)
                val rw = (0.15f + frac(h, 13) * 0.25f) * ppu
                r.set(sx(x) - rw, y - rw * 0.6f, sx(x) + rw, y + rw * 0.6f)
                c.drawOval(r, p)
            }
            n++
        }

        // rumble strips
        val period = 0.5f
        var k = floor(zMin / period).toInt()
        while (k * period < zMax) {
            p.color = if (k and 1 == 0) 0xFFE53935.toInt() else 0xFFF5F5F5.toInt()
            val y0 = sy((k + 1) * period, camZ)
            val y1 = sy(k * period, camZ)
            c.drawRect(sx(-half - 0.13f), y0, sx(-half), y1, p)
            c.drawRect(sx(half), y0, sx(half + 0.13f), y1, p)
            k++
        }
        // edge lines
        p.color = cMark
        c.drawRect(sx(-half + 0.05f), 0f, sx(-half + 0.1f), height.toFloat(), p)
        c.drawRect(sx(half - 0.1f), 0f, sx(half - 0.05f), height.toFloat(), p)
        // lane dashes
        val dash = 1.6f
        var d = floor(zMin / dash).toInt()
        while (d * dash < zMax) {
            val y0 = sy(d * dash + 0.85f, camZ)
            val y1 = sy(d * dash, camZ)
            for (l in 1 until GameConfig.LANES) {
                val x = -half + l * GameConfig.LANE_W
                c.drawRect(sx(x - 0.03f), y0, sx(x + 0.03f), y1, p)
            }
            d++
        }
    }

    private fun shoulderX(side: Int, h: Int, minOff: Float, extra: Float): Float {
        val half = GameConfig.LANES * GameConfig.LANE_W / 2f
        val shoulder = viewUnitsW / 2f - half
        return side * (half + minOff + frac(h, 7) * maxOf(0.05f, shoulder - minOff + extra))
    }

    private fun drawGroundDetails(c: Canvas, camZ: Float) {
        val tile = 0.9f
        val zMin = camZ - (height - playerY) / ppu - 1f
        val zMax = camZ + playerY / ppu + 1f
        var n = floor(zMin / tile).toInt()
        while (n * tile < zMax) {
            for (s in 0..1) {
                val side = if (s == 0) -1 else 1
                val h = hash(n, 101 + s)
                val x = sx(shoulderX(side, h, 0.25f, 0.2f))
                val y = sy(n * tile + frac(h, 11) * tile, camZ)
                when (h % 7) {
                    0, 1, 2 -> { // grass tuft
                        p.color = cGrassDark
                        val rr = 0.05f * ppu
                        c.drawCircle(x, y, rr, p)
                        c.drawCircle(x + rr * 1.4f, y + rr * 0.5f, rr * 0.8f, p)
                        c.drawCircle(x - rr * 1.2f, y + rr * 0.7f, rr * 0.7f, p)
                    }
                    3 -> { // flowers
                        p.color = FLOWERS[(h ushr 4) % FLOWERS.size]
                        p.alpha = (255 * (1f - 0.6f * night)).toInt()
                        c.drawCircle(x, y, 0.035f * ppu, p)
                        c.drawCircle(x + 0.09f * ppu, y - 0.05f * ppu, 0.03f * ppu, p)
                        p.alpha = 255
                    }
                    4 -> { // rock
                        p.color = lerpColor(0xFF9E9E9E.toInt(), 0xFF3A3D42.toInt(), night)
                        r.set(x - 0.09f * ppu, y - 0.06f * ppu, x + 0.09f * ppu, y + 0.06f * ppu)
                        c.drawOval(r, p)
                    }
                }
            }
            n++
        }
    }

    private fun drawTreesAndLamps(c: Canvas, camZ: Float) {
        // Tall scenery sits "closer" to the camera: parallax factor > 1 makes it scroll faster.
        val lz = camZ * TREE_PARALLAX
        val tile = 1.4f
        val zMin = lz - (height - playerY) / ppu - 1.5f
        val zMax = lz + playerY / ppu + 1.5f
        var n = floor(zMin / tile).toInt()
        while (n * tile < zMax) {
            for (s in 0..1) {
                val side = if (s == 0) -1 else 1
                val h = hash(n, 333 + s)
                if (h % 5 >= 3) continue
                val rad = 0.26f + frac(h, 5) * 0.24f
                val x = sx(shoulderX(side, h, 0.22f + rad, rad * 0.8f))
                val y = playerY - (n * tile + frac(h, 9) * tile - lz) * ppu
                val rp = rad * ppu
                p.color = 0x40000000
                c.drawCircle(x + 0.12f * ppu, y + 0.14f * ppu, rp, p)
                p.color = cTree
                c.drawCircle(x, y, rp, p)
                p.color = cTreeLight
                c.drawCircle(x - rp * 0.22f, y - rp * 0.22f, rp * 0.62f, p)
                p.color = 0x22FFFFFF
                c.drawCircle(x - rp * 0.35f, y - rp * 0.35f, rp * 0.25f, p)
            }
            n++
        }
        // street lamps (both sides, alternating)
        val lampTile = 3f
        var m = floor(zMin / lampTile).toInt()
        while (m * lampTile < zMax) {
            val side = if (m and 1 == 0) -1 else 1
            val x0 = sx(side * 1.78f)
            val x1 = sx(side * 1.42f)
            val y = playerY - (m * lampTile - lz) * ppu
            p.color = 0x50000000
            c.drawCircle(x0 + 0.08f * ppu, y + 0.1f * ppu, 0.07f * ppu, p)
            stroke.color = 0xFF5F6670.toInt()
            stroke.strokeWidth = 0.05f * ppu
            c.drawLine(x0, y, x1, y, stroke)
            p.color = 0xFF737B86.toInt()
            c.drawCircle(x0, y, 0.07f * ppu, p)
            p.color = lerpColor(0xFFCFD8DC.toInt(), 0xFFFFE6A8.toInt(), night)
            r.set(x1 - 0.07f * ppu, y - 0.045f * ppu, x1 + 0.07f * ppu, y + 0.045f * ppu)
            c.drawRoundRect(r, 0.03f * ppu, 0.03f * ppu, p)
            m++
        }
    }

    private fun drawClouds(c: Canvas, camZ: Float) {
        val a = (1f - night) * 0.22f
        if (a <= 0.01f) return
        val lz = camZ * CLOUD_PARALLAX
        val tile = 4.5f
        val zMin = lz - (height - playerY) / ppu - 3f
        val zMax = lz + playerY / ppu + 3f
        p.color = Color.WHITE
        p.alpha = (a * 255).toInt()
        var n = floor(zMin / tile).toInt()
        while (n * tile < zMax) {
            val h = hash(n, 999)
            if (h % 3 == 0) {
                val x = sx((frac(h, 3) - 0.5f) * viewUnitsW * 1.1f)
                val y = playerY - (n * tile - lz) * ppu
                val rad = (0.55f + frac(h, 15) * 0.5f) * ppu
                c.drawCircle(x, y, rad, p)
                c.drawCircle(x + rad * 0.8f, y + rad * 0.2f, rad * 0.75f, p)
                c.drawCircle(x - rad * 0.75f, y + rad * 0.25f, rad * 0.65f, p)
            }
            n++
        }
        p.alpha = 255
    }

    // ------------------------------------------------------------------ vehicles & objects

    private fun drawCar(c: Canvas, x: Float, y: Float, hw: Float, hl: Float, body: Int, rotDeg: Float, player: Boolean) {
        c.save()
        c.translate(sx(x), y)
        c.scale(ppu, ppu)
        if (rotDeg != 0f) c.rotate(rotDeg)
        p.color = 0x55000000
        r.set(-hw + 0.06f, -hl + 0.09f, hw + 0.06f, hl + 0.09f)
        c.drawRoundRect(r, 0.13f, 0.13f, p)
        p.color = 0xFF16181C.toInt()
        r.set(-hw - 0.035f, -hl * 0.74f, -hw + 0.06f, -hl * 0.4f); c.drawRoundRect(r, 0.03f, 0.03f, p)
        r.set(hw - 0.06f, -hl * 0.74f, hw + 0.035f, -hl * 0.4f); c.drawRoundRect(r, 0.03f, 0.03f, p)
        r.set(-hw - 0.035f, hl * 0.4f, -hw + 0.06f, hl * 0.74f); c.drawRoundRect(r, 0.03f, 0.03f, p)
        r.set(hw - 0.06f, hl * 0.4f, hw + 0.035f, hl * 0.74f); c.drawRoundRect(r, 0.03f, 0.03f, p)
        p.color = body
        r.set(-hw, -hl, hw, hl); c.drawRoundRect(r, 0.13f, 0.13f, p)
        p.color = lighten(body, 0.18f)
        r.set(-hw * 0.82f, -hl * 0.95f, hw * 0.82f, -hl * 0.55f); c.drawRoundRect(r, 0.08f, 0.08f, p)
        p.color = cGlass
        r.set(-hw * 0.78f, -hl * 0.52f, hw * 0.78f, -hl * 0.2f); c.drawRoundRect(r, 0.05f, 0.05f, p)
        p.color = darken(body, 0.15f)
        r.set(-hw * 0.8f, -hl * 0.2f, hw * 0.8f, hl * 0.42f); c.drawRoundRect(r, 0.07f, 0.07f, p)
        p.color = cGlass
        r.set(-hw * 0.72f, hl * 0.44f, hw * 0.72f, hl * 0.64f); c.drawRoundRect(r, 0.04f, 0.04f, p)
        if (player) {
            p.color = 0xEEFFFFFF.toInt()
            c.drawRect(-0.05f, -hl + 0.02f, -0.018f, hl - 0.02f, p)
            c.drawRect(0.018f, -hl + 0.02f, 0.05f, hl - 0.02f, p)
            p.color = 0xFF202020.toInt()
            r.set(-hw - 0.02f, hl - 0.07f, hw + 0.02f, hl + 0.02f); c.drawRoundRect(r, 0.02f, 0.02f, p)
        }
        p.color = 0xFFFFF4C8.toInt()
        r.set(-hw * 0.88f, -hl + 0.01f, -hw * 0.42f, -hl + 0.07f); c.drawRoundRect(r, 0.02f, 0.02f, p)
        r.set(hw * 0.42f, -hl + 0.01f, hw * 0.88f, -hl + 0.07f); c.drawRoundRect(r, 0.02f, 0.02f, p)
        p.color = 0xFFE53935.toInt()
        r.set(-hw * 0.88f, hl - 0.06f, -hw * 0.45f, hl - 0.005f); c.drawRect(r, p)
        r.set(hw * 0.45f, hl - 0.06f, hw * 0.88f, hl - 0.005f); c.drawRect(r, p)
        c.restore()
    }

    private fun drawTruck(c: Canvas, x: Float, y: Float, variant: Int) {
        val hw = ObstacleKind.HALF_W[ObstacleKind.TRUCK]
        val hl = ObstacleKind.HALF_L[ObstacleKind.TRUCK]
        c.save()
        c.translate(sx(x), y)
        c.scale(ppu, ppu)
        p.color = 0x55000000
        r.set(-hw + 0.08f, -hl + 0.1f, hw + 0.08f, hl + 0.12f); c.drawRoundRect(r, 0.06f, 0.06f, p)
        p.color = 0xFF16181C.toInt()
        for (wy in WHEEL_Y) {
            r.set(-hw - 0.03f, wy - 0.1f, hw + 0.03f, wy + 0.1f); c.drawRoundRect(r, 0.03f, 0.03f, p)
        }
        val cab = TRUCK_CABS[variant % TRUCK_CABS.size]
        p.color = cab
        r.set(-hw * 0.9f, -hl, hw * 0.9f, -hl + 0.55f); c.drawRoundRect(r, 0.1f, 0.1f, p)
        p.color = cGlass
        r.set(-hw * 0.75f, -hl + 0.06f, hw * 0.75f, -hl + 0.2f); c.drawRoundRect(r, 0.04f, 0.04f, p)
        p.color = darken(cab, 0.2f)
        r.set(-hw * 0.75f, -hl + 0.25f, hw * 0.75f, -hl + 0.5f); c.drawRoundRect(r, 0.04f, 0.04f, p)
        p.color = 0xFF2A2D33.toInt()
        c.drawRect(-0.06f, -hl + 0.55f, 0.06f, -hl + 0.63f, p)
        p.color = lerpColor(0xFFECEFF1.toInt(), 0xFF8D98A3.toInt(), night)
        r.set(-hw, -hl + 0.62f, hw, hl); c.drawRoundRect(r, 0.05f, 0.05f, p)
        p.color = TRUCK_STRIPES[variant % TRUCK_STRIPES.size]
        c.drawRect(-hw, -hl + 0.95f, hw, -hl + 1.1f, p)
        p.color = 0x22000000
        var ry = -hl + 1.25f
        while (ry < hl - 0.1f) { c.drawRect(-hw, ry, hw, ry + 0.025f, p); ry += 0.22f }
        p.color = 0xFFE53935.toInt()
        c.drawRect(-hw * 0.9f, hl - 0.05f, -hw * 0.5f, hl, p)
        c.drawRect(hw * 0.5f, hl - 0.05f, hw * 0.9f, hl, p)
        c.restore()
    }

    private fun drawBarrier(c: Canvas, x: Float, y: Float) {
        val hw = ObstacleKind.HALF_W[ObstacleKind.BARRIER]
        val hl = ObstacleKind.HALF_L[ObstacleKind.BARRIER]
        c.save()
        c.translate(sx(x), y)
        c.scale(ppu, ppu)
        p.color = 0x55000000
        r.set(-hw + 0.05f, -hl + 0.07f, hw + 0.05f, hl + 0.08f); c.drawRect(r, p)
        p.color = 0xFFF5F5F5.toInt()
        r.set(-hw, -hl * 0.6f, hw, hl * 0.6f); c.drawRect(r, p)
        p.color = 0xFFFF6D00.toInt()
        val seg = 2 * hw / 6f
        for (k in 0 until 3) c.drawRect(-hw + 2 * k * seg, -hl * 0.6f, -hw + (2 * k + 1) * seg, hl * 0.6f, p)
        p.color = 0xFF37474F.toInt()
        c.drawCircle(-hw * 0.85f, 0f, 0.075f, p)
        c.drawCircle(hw * 0.85f, 0f, 0.075f, p)
        p.color = 0xFFFFC400.toInt()
        c.drawCircle(-hw * 0.85f, 0f, 0.04f, p)
        c.drawCircle(hw * 0.85f, 0f, 0.04f, p)
        c.restore()
    }

    private fun drawOil(c: Canvas, x: Float, y: Float, variant: Int) {
        if (y < -2f * ppu || y > height + 2f * ppu) return
        val hw = ObstacleKind.HALF_W[ObstacleKind.OIL]
        val hl = ObstacleKind.HALF_L[ObstacleKind.OIL]
        c.save()
        c.translate(sx(x), y)
        c.scale(ppu, ppu)
        if (variant and 1 == 1) c.scale(-1f, 1f)
        p.color = 0xF0121212.toInt()
        r.set(-hw, -hl * 0.7f, hw * 0.6f, hl); c.drawOval(r, p)
        r.set(-hw * 0.4f, -hl, hw, hl * 0.35f); c.drawOval(r, p)
        p.color = 0x553F51B5
        r.set(-hw * 0.5f, -hl * 0.3f, hw * 0.1f, hl * 0.25f); c.drawOval(r, p)
        p.color = 0x4426A69A
        r.set(-hw * 0.1f, -hl * 0.7f, hw * 0.6f, -hl * 0.25f); c.drawOval(r, p)
        p.color = 0x33FFFFFF
        c.drawCircle(-hw * 0.3f, -hl * 0.1f, 0.05f, p)
        c.restore()
    }

    private fun drawPickup(c: Canvas, kind: Int, x: Float, y: Float, clock: Float, z: Float) {
        if (y < -1f * ppu || y > height + ppu) return
        c.save()
        c.translate(sx(x), y)
        c.scale(ppu, ppu)
        if (kind == PickupKind.COIN) {
            val squash = maxOf(0.18f, abs(cos(clock * 5f + z)))
            p.color = 0x40000000
            c.drawCircle(0.05f, 0.07f, 0.16f, p)
            c.scale(squash, 1f)
            p.color = 0xFFF9A825.toInt()
            c.drawCircle(0f, 0f, 0.17f, p)
            p.color = 0xFFFFD54F.toInt()
            c.drawCircle(0f, 0f, 0.13f, p)
            p.color = 0xFFFFF59D.toInt()
            c.drawRect(-0.025f, -0.08f, 0.025f, 0.08f, p)
        } else {
            val pulse = 1f + 0.12f * sin(clock * 6f)
            p.color = 0x5500E5FF
            c.drawCircle(0f, 0f, 0.3f * pulse, p)
            p.color = 0xFF0288D1.toInt()
            c.drawCircle(0f, 0f, 0.2f, p)
            p.color = Color.WHITE
            c.drawPath(shieldGlyph, p)
        }
        c.restore()
    }

    private fun drawShieldBubble(c: Canvas, x: Float, remaining: Float, clock: Float) {
        if (remaining < 1.5f && ((clock * 10f).toInt() and 1) == 1) return
        val rad = (0.72f + 0.04f * sin(clock * 8f)) * ppu
        p.color = 0x3300E5FF
        c.drawCircle(sx(x), playerY, rad, p)
        stroke.color = 0xCC4DD0E1.toInt()
        stroke.strokeWidth = 0.04f * ppu
        c.drawCircle(sx(x), playerY, rad, stroke)
    }

    private fun drawParticles(c: Canvas, fx: ParticleSystem, camZ: Float) {
        if (fx.alive == 0) return
        for (i in 0 until fx.capacity) {
            if (fx.life[i] <= 0f) continue
            val a = fx.alpha(i)
            val col = fx.color[i]
            p.color = col
            p.alpha = (Color.alpha(col) * a).toInt()
            c.drawCircle(sx(fx.x[i]), sy(fx.z[i], camZ), fx.size[i] * ppu, p)
        }
        p.alpha = 255
    }

    private fun drawNight(c: Canvas, world: World, f: FrameInfo) {
        c.drawColor(Color.argb((night * 120).toInt(), 6, 10, 32))
        val camZ = f.camZ
        val la = (night * 255).toInt()
        // Lamp glows (same parallax as lamp heads)
        val lz = camZ * TREE_PARALLAX
        val zMin = lz - (height - playerY) / ppu - 2f
        val zMax = lz + playerY / ppu + 2f
        glow.shader = lampShader
        glow.alpha = la
        var m = floor(zMin / 3f).toInt()
        while (m * 3f < zMax) {
            val side = if (m and 1 == 0) -1 else 1
            drawGlow(c, sx(side * 1.42f), playerY - (m * 3f - lz) * ppu, 1.3f)
            m++
        }
        // Headlights + taillights for every vehicle
        for (o in world.obstacles) {
            if (!o.active || (o.kind != ObstacleKind.SLOW_CAR && o.kind != ObstacleKind.TRUCK)) continue
            val y = sy(o.z, camZ)
            if (y < -4f * ppu || y > height + 3f * ppu) continue
            val hl = ObstacleKind.HALF_L[o.kind]
            drawCone(c, o.x, y - hl * ppu, 0f, la)
            glow.shader = redShader
            glow.alpha = la
            drawGlow(c, sx(o.x - 0.15f), y + hl * ppu, 0.22f)
            drawGlow(c, sx(o.x + 0.15f), y + hl * ppu, 0.22f)
        }
        for (o in world.obstacles) {
            if (!o.active || o.kind != ObstacleKind.BARRIER) continue
            val blink = 0.5f + 0.5f * sin(f.clock * 9f + o.z)
            glow.shader = amberShader
            glow.alpha = (la * blink).toInt()
            val y = sy(o.z, camZ)
            val hw = ObstacleKind.HALF_W[ObstacleKind.BARRIER] * 0.85f
            drawGlow(c, sx(o.x - hw), y, 0.35f)
            drawGlow(c, sx(o.x + hw), y, 0.35f)
        }
        if (f.showPlayer) {
            val hl = GameConfig.PLAYER_HALF_L
            drawCone(c, f.playerX, playerY, f.playerTilt + f.playerSpin, la, -hl)
            glow.shader = redShader
            glow.alpha = la
            drawGlow(c, sx(f.playerX - 0.15f), playerY + hl * ppu, 0.25f)
            drawGlow(c, sx(f.playerX + 0.15f), playerY + hl * ppu, 0.25f)
        }
        glow.shader = null
    }

    private fun drawCone(c: Canvas, x: Float, y: Float, rot: Float, alpha: Int, frontOffset: Float = 0f) {
        c.save()
        c.translate(sx(x), y)
        c.scale(ppu, ppu)
        if (rot != 0f) c.rotate(rot)
        c.translate(0f, frontOffset)
        glow.shader = coneShader
        glow.alpha = alpha
        c.drawPath(conePath, glow)
        c.restore()
    }

    private fun drawGlow(c: Canvas, x: Float, y: Float, radiusUnits: Float) {
        c.save()
        c.translate(x, y)
        val s = radiusUnits * ppu
        c.scale(s, s)
        c.drawCircle(0f, 0f, 1f, glow)
        c.restore()
    }

    private fun drawSpeedLines(c: Canvas, f: FrameInfo) {
        val sn = (f.speed - GameConfig.V_MIN) / (GameConfig.V_MAX - GameConfig.V_MIN)
        if (sn < 0.45f || !f.showHud) return
        val a = ((sn - 0.45f) / 0.55f).coerceIn(0f, 1f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 1.5f * dp
        for (i in 0 until SPEED_LINES) {
            val len = lineLen[i] * height
            val travel = height + len
            val y = ((lineY[i] * travel + f.clock * height * lineV[i] * (0.8f + sn)) % travel) - len
            stroke.alpha = (a * 110).toInt()
            val x = lineX[i] * width
            c.drawLine(x, y, x, y + len, stroke)
        }
    }

    private fun drawPopups(c: Canvas) {
        for (i in 0 until MAX_POPUPS) {
            val life = popLife[i]
            if (life <= 0f) continue
            val text = popText[i] ?: continue
            val t = 1f - life / POPUP_LIFE
            val a = if (life < 0.3f) life / 0.3f else 1f
            val pop = if (t < 0.12f) 0.6f + t / 0.12f * 0.5f else 1.1f - minOf(0.1f, (t - 0.12f))
            popPaint.textSize = popSize[i] * sp * pop
            popPaint.color = popColor[i]
            popPaint.alpha = (a * 255).toInt()
            val x = sx(popX[i])
            val y = playerY - popZ[i] * ppu - t * 70f * dp
            c.drawText(text, x, y, popPaint)
        }
    }

    private fun drawHud(c: Canvas, f: FrameInfo) {
        val top = insetTop + 14f * dp
        // Score (centre)
        hud.textAlign = Paint.Align.CENTER
        hud.textSize = 40f * sp
        hud.color = Color.WHITE
        var n = formatNumber(f.score, numBuf, true)
        c.drawText(numBuf, 0, n, width / 2f, top + 40f * sp, hud)
        var below = top + 52f * sp
        if (f.combo >= 2) {
            hudSmall.textAlign = Paint.Align.CENTER
            hudSmall.textSize = 15f * sp
            hudSmall.color = 0xFFFFD54A.toInt()
            c.drawText(COMBO_LABELS[f.combo.coerceAtMost(COMBO_LABELS.size - 1)], width / 2f, below + 12f * sp, hudSmall)
            below += 20f * sp
        }
        // Shield timer bar
        if (f.shield > 0f) {
            val bw = 120f * dp
            val bh = 8f * dp
            val x0 = width / 2f - bw / 2f
            val y0 = below + 6f * dp
            p.color = 0x88000000.toInt()
            r.set(x0, y0, x0 + bw, y0 + bh); c.drawRoundRect(r, bh / 2, bh / 2, p)
            val frac = (f.shield / GameConfig.SHIELD_TIME).coerceIn(0f, 1f)
            val blinkOff = f.shield < 1.5f && ((f.clock * 10f).toInt() and 1) == 1
            p.color = if (blinkOff) 0xFF80DEEA.toInt() else 0xFF00E5FF.toInt()
            r.set(x0, y0, x0 + bw * frac, y0 + bh); c.drawRoundRect(r, bh / 2, bh / 2, p)
            hudSmall.textAlign = Paint.Align.CENTER
            hudSmall.textSize = 11f * sp
            hudSmall.color = 0xFFB2EBF2.toInt()
            n = formatTenths(f.shield, numBuf)
            c.drawText(numBuf, 0, n, width / 2f, y0 + bh + 14f * sp, hudSmall)
        }
        // Speed (top-left)
        hud.textAlign = Paint.Align.LEFT
        hud.textSize = 26f * sp
        val sn = (f.speed - GameConfig.V_MIN) / (GameConfig.V_MAX - GameConfig.V_MIN)
        hud.color = lerpColor(Color.WHITE, 0xFFFF7A1A.toInt(), sn.coerceIn(0f, 1f))
        n = formatNumber((f.speed * GameConfig.KMH_PER_UNIT).toLong(), numBuf, false)
        val lx = 18f * dp
        c.drawText(numBuf, 0, n, lx, top + 26f * sp, hud)
        hudSmall.textAlign = Paint.Align.LEFT
        hudSmall.textSize = 11f * sp
        hudSmall.color = 0xCCFFFFFF.toInt()
        c.drawText(KMH, lx, top + 40f * sp, hudSmall)
        // Coins
        val cy = top + 60f * sp
        p.color = 0xFFF9A825.toInt()
        c.drawCircle(lx + 7f * dp, cy - 5f * dp, 7f * dp, p)
        p.color = 0xFFFFD54F.toInt()
        c.drawCircle(lx + 7f * dp, cy - 5f * dp, 5f * dp, p)
        hudSmall.textSize = 15f * sp
        hudSmall.color = Color.WHITE
        n = formatNumber(f.coins.toLong(), numBuf, false)
        c.drawText(numBuf, 0, n, lx + 19f * dp, cy, hudSmall)
        hud.color = Color.WHITE
    }

    companion object {
        const val VIEW_W = 5.2f
        const val MIN_VIEW_H = 11f
        const val PLAYER_FROM_BOTTOM = 2.1f
        const val TREE_PARALLAX = 1.12f
        const val CLOUD_PARALLAX = 1.6f
        const val DAY_NIGHT_PERIOD = 120f
        const val TRANSITION = 6f
        const val MAX_POPUPS = 10
        const val POPUP_LIFE = 0.9f
        const val SPEED_LINES = 18
        private const val KMH = "KM/H"

        val PLAYER_COLOR = 0xFFE53935.toInt()
        private val CAR_COLORS = intArrayOf(
            0xFF1E88E5.toInt(), 0xFFFDD835.toInt(), 0xFF8E24AA.toInt(), 0xFF43A047.toInt(),
            0xFFECEFF1.toInt(), 0xFF00897B.toInt(), 0xFFFB8C00.toInt(), 0xFF546E7A.toInt(),
        )
        private val TRUCK_CABS = intArrayOf(0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFF9A825.toInt(), 0xFF6D4C41.toInt())
        private val TRUCK_STRIPES = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFF6F00.toInt())
        private val FLOWERS = intArrayOf(0xFFFFEB3B.toInt(), 0xFFFFFFFF.toInt(), 0xFFF48FB1.toInt(), 0xFFCE93D8.toInt())
        private val WHEEL_Y = floatArrayOf(-0.95f, -0.2f, 0.75f, 1.0f)
        private val COMBO_LABELS = arrayOf("", "", "COMBO x2", "COMBO x3", "COMBO x4", "COMBO x5")

        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            val aa = (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt()
            val rr = (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt()
            val gg = (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt()
            val bb = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt()
            return Color.argb(aa, rr, gg, bb)
        }

        fun lighten(c: Int, t: Float) = lerpColor(c, Color.WHITE or (c and 0xFF000000.toInt()), t)
        fun darken(c: Int, t: Float) = lerpColor(c, Color.BLACK or (c and 0xFF000000.toInt()), t)

        fun hash(n: Int, salt: Int): Int {
            var x = n * 0x27d4eb2d xor (salt * 0x165667b1)
            x = x xor (x ushr 15)
            x *= 0x2c1b3c6d
            x = x xor (x ushr 12)
            x *= 0x297a2d39
            x = x xor (x ushr 15)
            return x and 0x7fffffff
        }

        fun frac(h: Int, shift: Int): Float = ((h ushr shift) and 1023) / 1023f

        /** Writes [v] into [out] (optionally with thousands separators); returns length. No allocation. */
        fun formatNumber(v: Long, out: CharArray, grouping: Boolean): Int {
            var x = if (v < 0) 0L else v
            var len = 0
            var digits = 0
            do {
                if (grouping && digits > 0 && digits % 3 == 0) out[len++] = ','
                out[len++] = ('0' + (x % 10).toInt())
                x /= 10
                digits++
            } while (x > 0 && len < out.size - 1)
            var i = 0
            var j = len - 1
            while (i < j) { val t = out[i]; out[i] = out[j]; out[j] = t; i++; j-- }
            return len
        }

        /** "4.2s" style; no allocation. */
        fun formatTenths(seconds: Float, out: CharArray): Int {
            val tenths = (seconds * 10f).toInt().coerceAtLeast(0)
            var len = formatNumber((tenths / 10).toLong(), out, false)
            out[len++] = '.'
            out[len++] = '0' + tenths % 10
            out[len++] = 's'
            return len
        }
    }
}
