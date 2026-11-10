package com.endlessdrive.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.endlessdrive.core.GameConfig
import com.endlessdrive.core.GameSession
import com.endlessdrive.core.Mode
import kotlin.math.hypot

/**
 * 2D overlay: HUD (score, speed, boost meter, shield timer, combo, popups) and the on-screen
 * controls (analog touch-steering pad, BRAKE, BOOST). Multi-touch: steer with one thumb while
 * braking/boosting with the other. Drawing allocates nothing (char buffers, preallocated paints).
 */
class HudView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var session: GameSession? = null
    var tiltMode = false
    /** Called once per drawn frame (engine sound update etc.). */
    var onFrame: Runnable? = null

    private val dp = resources.displayMetrics.density
    private val sp = dp * resources.configuration.fontScale
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        color = Color.WHITE
        setShadowLayer(6f * dp, 0f, 2f * dp, 0xAA000000.toInt())
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = 0xDDFFFFFF.toInt()
        setShadowLayer(4f * dp, 0f, 1f * dp, 0xAA000000.toInt())
    }
    private val pop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.ITALIC)
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f * dp, 0f, 2f * dp, 0xCC000000.toInt())
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val buf = CharArray(24)

    // controls geometry (computed in onSizeChanged)
    private var brakeX = 0f; private var brakeY = 0f; private var brakeR = 0f
    private var boostX = 0f; private var boostY = 0f; private var boostR = 0f
    private var steerZoneRight = 0f
    private val steerRange = 64f * dp

    // touch state
    private var steerId = -1
    private var steerAnchor = 0f
    private var steerX = 0f
    private var brakeId = -1
    private var boostFlash = 0f

    // popup animation
    private var seenPopup = 0
    private var popupT = 99f
    private var popupKind = 0
    private var popupPoints = 0
    private var lastFrameNanos = 0L

    var insetLeft = 0f
    var insetRight = 0f
    var insetTop = 0f
    var insetBottom = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutControls()

    fun setInsets(l: Int, t: Int, rr: Int, b: Int) {
        insetLeft = l.toFloat(); insetTop = t.toFloat(); insetRight = rr.toFloat(); insetBottom = b.toFloat()
        layoutControls()
    }

    private fun layoutControls() {
        brakeR = 50f * dp
        boostR = 42f * dp
        brakeX = width - insetRight - 28f * dp - brakeR
        brakeY = height - insetBottom - 26f * dp - brakeR
        boostX = brakeX - brakeR - 26f * dp - boostR
        boostY = brakeY + brakeR - boostR - 6f * dp
        steerZoneRight = width * 0.55f
    }

    private fun playing(): Boolean = session?.mode == Mode.PLAYING

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = session ?: return false
        if (!playing()) {
            releaseAll(s)
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i); val y = e.getY(i)
                if (hypot(x - brakeX, y - brakeY) < brakeR * 1.25f) {
                    brakeId = id
                    s.input.brake = true
                } else if (hypot(x - boostX, y - boostY) < boostR * 1.3f) {
                    s.input.boostPresses = s.input.boostPresses + 1
                    boostFlash = 1f
                } else if (!tiltMode && x < steerZoneRight && steerId == -1) {
                    steerId = id
                    steerAnchor = x
                    steerX = x
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (k in 0 until e.pointerCount) {
                    if (e.getPointerId(k) == steerId) {
                        steerX = e.getX(k)
                        s.input.steer = ((steerX - steerAnchor) / steerRange).coerceIn(-1f, 1f)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == steerId) { steerId = -1; s.input.steer = 0f }
                if (id == brakeId) { brakeId = -1; s.input.brake = false }
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> releaseAll(s)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun releaseAll(s: GameSession) {
        if (steerId != -1 && !tiltMode) s.input.steer = 0f
        steerId = -1
        if (brakeId != -1) s.input.brake = false
        brakeId = -1
    }

    override fun onDraw(c: Canvas) {
        val s = session ?: return
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        onFrame?.run()
        val h = s.hud
        val mode = h.mode
        if (mode == Mode.PLAYING || mode == Mode.PAUSED || mode == Mode.CRASHING) {
            drawHud(c, s, dt)
            if (mode == Mode.PLAYING) drawControls(c, s, dt)
        }
        if (mode == Mode.PLAYING || mode == Mode.CRASHING || mode == Mode.ATTRACT) postInvalidateOnAnimation()
    }

    private fun drawHud(c: Canvas, s: GameSession, dt: Float) {
        val h = s.hud
        val left = insetLeft + 20f * dp
        val top = insetTop + 14f * dp
        // score
        big.textAlign = Paint.Align.LEFT
        big.textSize = 34f * sp
        big.color = Color.WHITE
        var n = formatNumber(h.score, buf, true)
        c.drawText(buf, 0, n, left, top + 34f * sp, big)
        small.textAlign = Paint.Align.LEFT
        small.textSize = 11f * sp
        small.color = 0xCCFFFFFF.toInt()
        c.drawText(SCORE, left, top + 48f * sp, small)
        // shield timer
        if (h.shield > 0f) {
            val bw = 130f * dp; val bh = 8f * dp
            val y0 = top + 58f * sp
            fill.color = 0x88000000.toInt()
            r.set(left, y0, left + bw, y0 + bh); c.drawRoundRect(r, bh / 2, bh / 2, fill)
            val f = (h.shield / GameConfig.SHIELD_TIME).coerceIn(0f, 1f)
            val blink = h.shield < 1.5f && ((System.nanoTime() / 100_000_000L) and 1L) == 1L
            fill.color = if (blink) 0xFF80DEEA.toInt() else 0xFF00E5FF.toInt()
            r.set(left, y0, left + bw * f, y0 + bh); c.drawRoundRect(r, bh / 2, bh / 2, fill)
            small.color = 0xFFB2EBF2.toInt()
            n = formatTenths(h.shield, buf, SHIELD_PREFIX)
            c.drawText(buf, 0, n, left + bw + 8f * dp, y0 + bh, small)
        }
        // combo multiplier (top centre)
        if (h.combo >= 2) {
            big.textAlign = Paint.Align.CENTER
            big.textSize = 30f * sp
            big.color = 0xFFFFD54A.toInt()
            buf[0] = 'x'
            n = 1 + formatNumber(h.combo.toLong(), buf, false, 1)
            c.drawText(buf, 0, n, width / 2f, top + 30f * sp, big)
            small.textAlign = Paint.Align.CENTER
            small.color = 0xFFFFE082.toInt()
            c.drawText(COMBO, width / 2f, top + 44f * sp, small)
            val bw = 90f * dp
            fill.color = 0xFFFFD54A.toInt()
            r.set(width / 2f - bw / 2, top + 50f * sp, width / 2f - bw / 2 + bw * (h.comboTimer / GameConfig.COMBO_WINDOW), top + 50f * sp + 4f * dp)
            c.drawRect(r, fill)
        }
        // popups
        if (h.popupSeq != seenPopup) {
            seenPopup = h.popupSeq
            popupT = 0f
            popupKind = h.popupKind
            popupPoints = h.popupPoints
        }
        if (popupT < POPUP_TIME) {
            popupT += dt
            val t = popupT / POPUP_TIME
            val a = if (t > 0.7f) (1f - t) / 0.3f else 1f
            val scale = if (t < 0.1f) 0.7f + t * 4f else 1.1f - 0.1f * t
            pop.textSize = 26f * sp * scale
            pop.color = POPUP_COLORS[popupKind]
            pop.alpha = (a * 255).toInt()
            val y = height * 0.32f - t * 30f * dp
            val label = POPUP_LABELS[popupKind]
            if (popupPoints > 0) {
                val w0 = pop.measureText(label)
                n = 0
                buf[n++] = '+'
                n += formatNumber(popupPoints.toLong(), buf, false, n) - n
                val w1 = pop.measureText(buf, 0, n)
                val gap = 10f * dp
                val x0 = width / 2f - (w0 + gap + w1) / 2f
                c.drawText(label, x0 + w0 / 2f, y, pop)
                c.drawText(buf, 0, n, x0 + w0 + gap + w1 / 2f, y, pop)
            } else {
                c.drawText(label, width / 2f, y, pop)
            }
        }
        // speedometer + boost meter (bottom centre)
        val cx = width / 2f
        val by = height - insetBottom - 22f * dp
        big.textAlign = Paint.Align.CENTER
        big.textSize = 40f * sp
        val sn = ((h.speedKmh / GameConfig.KMH_PER_MS - GameConfig.V0) / (GameConfig.V_MAX_PLAYER - GameConfig.V0)).coerceIn(0f, 1f)
        big.color = mix(Color.WHITE, 0xFFFF7A1A.toInt(), sn)
        n = formatNumber(h.speedKmh.toLong(), buf, false)
        c.drawText(buf, 0, n, cx, by - 22f * dp, big)
        small.textAlign = Paint.Align.CENTER
        small.textSize = 11f * sp
        small.color = 0xCCFFFFFF.toInt()
        c.drawText(KMH, cx, by - 8f * dp, small)
        val bw = 150f * dp; val bh = 7f * dp
        fill.color = 0x88000000.toInt()
        r.set(cx - bw / 2, by, cx + bw / 2, by + bh); c.drawRoundRect(r, bh / 2, bh / 2, fill)
        fill.color = if (h.boosting) 0xFF40C4FF.toInt() else if (h.boostReady >= 1f) 0xFF2EC4B6.toInt() else 0xFF607D8B.toInt()
        r.set(cx - bw / 2, by, cx - bw / 2 + bw * h.boostReady, by + bh); c.drawRoundRect(r, bh / 2, bh / 2, fill)
        if (h.onShoulder && ((System.nanoTime() / 250_000_000L) and 1L) == 0L) {
            pop.textSize = 16f * sp
            pop.color = 0xFFFFB300.toInt()
            c.drawText(OFF_ROAD, cx, by - 70f * dp, pop)
        }
    }

    private fun drawControls(c: Canvas, s: GameSession, dt: Float) {
        val h = s.hud
        // brake
        fill.color = if (brakeId != -1) 0xCCE53935.toInt() else 0x66000000
        c.drawCircle(brakeX, brakeY, brakeR, fill)
        ring.color = 0xAAFFFFFF.toInt(); ring.strokeWidth = 2f * dp
        c.drawCircle(brakeX, brakeY, brakeR, ring)
        small.textAlign = Paint.Align.CENTER
        small.textSize = 14f * sp
        small.color = Color.WHITE
        c.drawText(BRAKE, brakeX, brakeY + 5f * sp, small)
        // boost with cooldown ring
        if (boostFlash > 0f) boostFlash = (boostFlash - dt * 3f).coerceAtLeast(0f)
        fill.color = if (h.boosting) 0xCC0091EA.toInt() else if (h.boostReady >= 1f) 0x9900A896.toInt() else 0x66000000
        c.drawCircle(boostX, boostY, boostR, fill)
        ring.color = if (h.boostReady >= 1f) 0xFF64FFDA.toInt() else 0xFF90A4AE.toInt()
        ring.strokeWidth = 4f * dp
        r.set(boostX - boostR, boostY - boostR, boostX + boostR, boostY + boostR)
        c.drawArc(r, -90f, 360f * h.boostReady, false, ring)
        small.textSize = 13f * sp
        c.drawText(BOOST, boostX, boostY + 5f * sp, small)
        // steering pad
        if (!tiltMode) {
            val padY = height - insetBottom - 60f * dp
            if (steerId != -1) {
                fill.color = 0x44FFFFFF
                r.set(steerAnchor - steerRange, padY - 6f * dp, steerAnchor + steerRange, padY + 6f * dp)
                c.drawRoundRect(r, 6f * dp, 6f * dp, fill)
                fill.color = 0xDDFFFFFF.toInt()
                val kx = steerAnchor + (steerX - steerAnchor).coerceIn(-steerRange, steerRange)
                c.drawCircle(kx, padY, 22f * dp, fill)
            } else {
                small.textAlign = Paint.Align.LEFT
                small.textSize = 12f * sp
                small.color = 0x99FFFFFF.toInt()
                c.drawText(STEER_HINT, insetLeft + 24f * dp, padY + 4f * dp, small)
                ring.color = 0x55FFFFFF; ring.strokeWidth = 2f * dp
                r.set(insetLeft + 20f * dp, padY - 26f * dp, steerZoneRight * 0.7f, padY + 26f * dp)
                c.drawRoundRect(r, 26f * dp, 26f * dp, ring)
            }
        } else {
            val padY = height - insetBottom - 60f * dp
            val x0 = insetLeft + 30f * dp
            val w = 140f * dp
            fill.color = 0x44FFFFFF
            r.set(x0, padY - 4f * dp, x0 + w, padY + 4f * dp); c.drawRoundRect(r, 4f * dp, 4f * dp, fill)
            fill.color = 0xDDFFFFFF.toInt()
            c.drawCircle(x0 + w / 2f + s.input.steer * w / 2f, padY, 10f * dp, fill)
            small.textAlign = Paint.Align.LEFT
            small.textSize = 12f * sp
            small.color = 0x99FFFFFF.toInt()
            c.drawText(TILT_HINT, x0, padY - 14f * dp, small)
        }
    }

    companion object {
        private const val SCORE = "SCORE"
        private const val COMBO = "COMBO"
        private const val KMH = "KM/H"
        private const val BRAKE = "BRAKE"
        private const val BOOST = "BOOST"
        private const val OFF_ROAD = "OFF ROAD"
        private const val STEER_HINT = "drag to steer"
        private const val TILT_HINT = "tilt to steer"
        private const val SHIELD_PREFIX = "SHIELD "
        private const val POPUP_TIME = 1.1f
        private val POPUP_LABELS = arrayOf("NEAR MISS", "CLOSE CALL!", "SHIELD!", "SHIELD SAVED YOU", "COIN")
        private val POPUP_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFFF7A1A.toInt(), 0xFF4DD0E1.toInt(), 0xFF80DEEA.toInt(), 0xFFFFD54A.toInt(),
        )

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
            )
        }

        /** Writes v at [start] in out (optional thousands separators). Returns the end index. No allocation. */
        fun formatNumber(v: Long, out: CharArray, grouping: Boolean, start: Int = 0): Int {
            var x = if (v < 0) 0L else v
            var len = start
            var digits = 0
            do {
                if (grouping && digits > 0 && digits % 3 == 0) out[len++] = ','
                out[len++] = '0' + (x % 10).toInt()
                x /= 10
                digits++
            } while (x > 0 && len < out.size - 1)
            var i = start
            var j = len - 1
            while (i < j) { val t = out[i]; out[i] = out[j]; out[j] = t; i++; j-- }
            return len
        }

        /** "SHIELD 4.2s"; no allocation. */
        fun formatTenths(seconds: Float, out: CharArray, prefix: String): Int {
            var n = 0
            for (ch in prefix) out[n++] = ch
            val tenths = (seconds * 10f).toInt().coerceAtLeast(0)
            n = formatNumber((tenths / 10).toLong(), out, false, n)
            out[n++] = '.'
            out[n++] = '0' + tenths % 10
            out[n++] = 's'
            return n
        }
    }
}
