package com.endlessdrive.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.endlessdrive.core.Cfg
import com.endlessdrive.core.World
import kotlin.math.abs

/**
 * In-game HUD (score, speed, boost meter, shield timer, combo) and the on-screen controls
 * (analog steering pad, brake, nitro, pause). Drawn with Canvas over the GL surface.
 * Numbers are formatted into a reusable char buffer: onDraw allocates nothing.
 */
class HudView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var controller: GameController? = null
    var onPauseTapped: (() -> Unit)? = null
    /** true while a run is on screen and accepting input. */
    var active = false
        set(v) {
            field = v
            if (!v) releaseAll()
            invalidate()
        }
    var tiltMode = false
        set(v) { field = v; releaseAll(); invalidate() }
    private var insetL = 0f; private var insetT = 0f; private var insetR = 0f; private var insetB = 0f

    private val d = resources.displayMetrics.density
    private val bold = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = bold; setShadowLayer(4f * d, 0f, 1.5f * d, 0xB0000000.toInt()) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD0FFFFFF.toInt(); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setShadowLayer(3f * d, 0f, 1f * d, 0xB0000000.toInt()) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val buf = CharArray(16)

    // control geometry (px), computed in layout()
    private var nitroX = 0f; private var nitroY = 0f; private var nitroR = 0f
    private var brakeX = 0f; private var brakeY = 0f; private var brakeR = 0f
    private var pauseX = 0f; private var pauseY = 0f; private var pauseR = 0f
    private var steerRange = 0f
    private var padY = 0f

    // pointers
    private var steerId = -1; private var steerAnchor = 0f; private var steerNow = 0f
    private var brakeId = -1; private var nitroId = -1

    fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        insetL = l.toFloat(); insetT = t.toFloat(); insetR = r.toFloat(); insetB = b.toFloat()
        layoutControls()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutControls()

    private fun layoutControls() {
        val w = width.toFloat(); val h = height.toFloat()
        nitroR = 44f * d; brakeR = 38f * d; pauseR = 20f * d
        nitroX = w - insetR - 70f * d; nitroY = h - insetB - 84f * d
        brakeX = nitroX - 112f * d; brakeY = h - insetB - 58f * d
        pauseX = w - insetR - 34f * d; pauseY = insetT + 34f * d
        steerRange = 62f * d
        padY = h - insetB - 64f * d
    }

    private fun releaseAll() {
        steerId = -1; brakeId = -1; nitroId = -1
        controller?.let { if (!tiltMode) it.steer = 0f; it.brake = false; it.nitro = false }
    }

    // ------------------------------------------------------------------ input

    private fun inCircle(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean {
        val dx = x - cx; val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = controller ?: return false
        if (!active) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i); val y = e.getY(i)
                if (inCircle(x, y, pauseX, pauseY, pauseR * 1.6f)) {
                    onPauseTapped?.invoke()
                    return true
                }
                if (inCircle(x, y, nitroX, nitroY, nitroR * 1.25f)) { nitroId = id; c.nitro = true }
                else if (inCircle(x, y, brakeX, brakeY, brakeR * 1.3f)) { brakeId = id; c.brake = true }
                else if (x < width * 0.55f) {
                    if (tiltMode) { brakeId = id; c.brake = true }
                    else if (steerId < 0) { steerId = id; steerAnchor = x; steerNow = x; c.steer = 0f }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (steerId >= 0) {
                    val i = e.findPointerIndex(steerId)
                    if (i >= 0) {
                        steerNow = e.getX(i)
                        // let the anchor follow once the thumb travels past full lock, so reversing is immediate
                        if (steerNow - steerAnchor > steerRange) steerAnchor = steerNow - steerRange
                        if (steerAnchor - steerNow > steerRange) steerAnchor = steerNow + steerRange
                        c.steer = shape((steerNow - steerAnchor) / steerRange)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == steerId) { steerId = -1; c.steer = 0f }
                if (id == brakeId) { brakeId = -1; c.brake = false }
                if (id == nitroId) { nitroId = -1; c.nitro = false }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    // ------------------------------------------------------------------ drawing

    private fun digits(value: Int): Int {
        var v = if (value < 0) 0 else value
        var n = 0
        do { buf[buf.size - 1 - n] = ('0' + v % 10); v /= 10; n++ } while (v > 0 && n < buf.size)
        return n
    }

    private fun drawNumber(canvas: Canvas, value: Int, x: Float, y: Float, p: Paint) {
        val n = digits(value)
        canvas.drawText(buf, buf.size - n, n, x, y, p)
    }

    override fun onDraw(canvas: Canvas) {
        val c = controller ?: return
        if (!active) return
        val w = c.world
        val left = insetL + 18f * d
        val top = insetT + 14f * d

        // score
        label.textSize = 11f * d; label.textAlign = Paint.Align.LEFT
        canvas.drawText("SCORE", left, top + 10f * d, label)
        text.textSize = 30f * d; text.textAlign = Paint.Align.LEFT; text.color = 0xFFFFFFFF.toInt()
        drawNumber(canvas, w.score, left, top + 40f * d, text)

        // speed
        val speedRight = pauseX - pauseR - 18f * d
        text.textAlign = Paint.Align.RIGHT; text.textSize = 30f * d
        text.color = if (w.boosting) 0xFF7FD8FF.toInt() else 0xFFFFFFFF.toInt()
        drawNumber(canvas, (w.speed * Cfg.KMH).toInt(), speedRight, top + 40f * d, text)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText("KM/H", speedRight, top + 10f * d, label)

        // pause button
        fill.color = 0x66000000
        canvas.drawCircle(pauseX, pauseY, pauseR, fill)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(pauseX - 7f * d, pauseY - 8f * d, pauseX - 2.5f * d, pauseY + 8f * d, fill)
        canvas.drawRect(pauseX + 2.5f * d, pauseY - 8f * d, pauseX + 7f * d, pauseY + 8f * d, fill)

        // shield timer
        if (w.shieldTime > 0f) {
            val y = top + 58f * d
            fill.color = 0xFF49B6FF.toInt()
            canvas.drawCircle(left + 8f * d, y, 8f * d, fill)
            fill.color = 0x66000000
            rect.set(left + 24f * d, y - 5f * d, left + 124f * d, y + 5f * d)
            canvas.drawRoundRect(rect, 5f * d, 5f * d, fill)
            fill.color = 0xFF49B6FF.toInt()
            rect.right = left + 24f * d + 100f * d * (w.shieldTime / Cfg.SHIELD_DURATION)
            canvas.drawRoundRect(rect, 5f * d, 5f * d, fill)
            label.textAlign = Paint.Align.LEFT
            canvas.drawText("SHIELD", left + 132f * d, y + 4f * d, label)
        }

        // combo multiplier + near-miss popup
        val cx = width * 0.5f
        if (w.combo.count > 0) {
            text.textAlign = Paint.Align.CENTER; text.textSize = 34f * d; text.color = 0xFFFFD43A.toInt()
            buf[buf.size - 1] = ('0' + w.combo.multiplier)
            buf[buf.size - 2] = 'x'
            canvas.drawText(buf, buf.size - 2, 2, cx, top + 36f * d, text)
            fill.color = 0x66000000
            rect.set(cx - 50f * d, top + 44f * d, cx + 50f * d, top + 50f * d)
            canvas.drawRoundRect(rect, 3f * d, 3f * d, fill)
            fill.color = 0xFFFFD43A.toInt()
            rect.right = cx - 50f * d + 100f * d * (w.combo.timer / Cfg.COMBO_WINDOW)
            canvas.drawRoundRect(rect, 3f * d, 3f * d, fill)
            label.textAlign = Paint.Align.CENTER
            canvas.drawText("COMBO", cx, top + 4f * d, label)
        }
        if (w.sinceNearMiss < 1.1f) {
            val k = w.sinceNearMiss / 1.1f
            text.textAlign = Paint.Align.CENTER; text.textSize = 20f * d
            text.color = ((255 * (1f - k * k)).toInt() shl 24) or 0xFFFFFF
            val y = top + 84f * d - 18f * d * k
            buf[buf.size - 1 - digits(w.lastNearMissPoints)] = '+'
            val n = digits(w.lastNearMissPoints) + 1
            canvas.drawText("NEAR MISS", cx - 8f * d, y, text)
            text.textAlign = Paint.Align.LEFT; text.color = (text.color and 0xFF000000.toInt()) or 0xFFD43A
            canvas.drawText(buf, buf.size - n, n, cx + 52f * d, y, text)
        }

        // nitro button with boost meter ring
        val ready = w.boostCharge >= 1f
        fill.color = if (w.boosting) 0xAA2A8FE8.toInt() else if (ready) 0x8849B6FF.toInt() else 0x55000000
        canvas.drawCircle(nitroX, nitroY, nitroR, fill)
        stroke.strokeWidth = 5f * d; stroke.color = 0x55FFFFFF
        canvas.drawCircle(nitroX, nitroY, nitroR, stroke)
        stroke.color = if (ready) 0xFF7FD8FF.toInt() else 0xFFFFFFFF.toInt()
        rect.set(nitroX - nitroR, nitroY - nitroR, nitroX + nitroR, nitroY + nitroR)
        canvas.drawArc(rect, -90f, 360f * w.boostCharge, false, stroke)
        text.textAlign = Paint.Align.CENTER; text.textSize = 14f * d
        text.color = if (ready || w.boosting) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
        canvas.drawText("NITRO", nitroX, nitroY + 5f * d, text)

        // brake button
        fill.color = if (c.brake) 0xAAE2231A.toInt() else 0x55000000
        canvas.drawCircle(brakeX, brakeY, brakeR, fill)
        stroke.strokeWidth = 3f * d; stroke.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(brakeX, brakeY, brakeR, stroke)
        text.color = 0xFFFFFFFF.toInt(); text.textSize = 13f * d
        canvas.drawText("BRAKE", brakeX, brakeY + 5f * d, text)

        // steering pad (touch mode) or tilt hint
        if (!tiltMode) {
            val ax = if (steerId >= 0) steerAnchor else insetL + 120f * d
            val kx = if (steerId >= 0) steerNow.coerceIn(ax - steerRange, ax + steerRange) else ax
            stroke.strokeWidth = 10f * d; stroke.color = 0x44000000
            canvas.drawLine(ax - steerRange, padY, ax + steerRange, padY, stroke)
            stroke.strokeWidth = 2f * d; stroke.color = 0x88FFFFFF.toInt()
            canvas.drawLine(ax - steerRange, padY, ax + steerRange, padY, stroke)
            fill.color = if (steerId >= 0) 0xDDFFFFFF.toInt() else 0x88FFFFFF.toInt()
            canvas.drawCircle(kx, padY, 20f * d, fill)
            if (steerId < 0) {
                label.textAlign = Paint.Align.CENTER
                canvas.drawText("DRAG TO STEER", ax, padY - 32f * d, label)
            }
        } else {
            label.textAlign = Paint.Align.LEFT
            canvas.drawText("TILT TO STEER  ·  HOLD LEFT SIDE TO BRAKE", left, height - insetB - 22f * d, label)
        }
    }

    companion object {
        /** Response curve: finer control around the centre, full authority at the ends. */
        fun shape(v: Float): Float {
            val c = v.coerceIn(-1f, 1f)
            return c * (0.5f + 0.5f * abs(c))
        }
    }
}
