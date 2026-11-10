package com.endlessdrive.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.endlessdrive.core.GameConfig
import com.endlessdrive.core.GameEvents
import com.endlessdrive.core.World
import com.endlessdrive.core.fx.ParticleSystem
import kotlin.math.abs

/**
 * Hosts the simulation loop (Choreographer-driven, hardware-accelerated Canvas) and input.
 * Game rules live in :core; this class only wires time, touch and drawing together.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback, GameEvents {

    enum class Mode { ATTRACT, PLAYING, PAUSED, CRASHED }

    interface Listener {
        fun onCrash()
        fun onCrashSequenceDone()
        fun onFeedback(event: Int, combo: Int)
        fun onTick(speedNorm: Float) {}
    }

    var listener: Listener? = null
    val world = World(1L, this)
    val fx = ParticleSystem()
    private val renderer = Renderer(context)
    private val frame = FrameInfo()

    var mode = Mode.ATTRACT
        private set
    private var running = false
    private var lastNanos = 0L
    private var clock = 0f
    private var attractZ = 0f
    private var attractCycle = 0f
    private var crashT = 0f
    private var crashNotified = false
    private var shake = 0f
    private var flash = 0f
    private var spin = 0f

    // Touch tracking (per pointer id, fixed arrays: no allocation)
    private val downX = FloatArray(MAX_POINTERS)
    private val downY = FloatArray(MAX_POINTERS)
    private val swiped = BooleanArray(MAX_POINTERS)
    private val swipeThreshold = 28f * resources.displayMetrics.density

    init {
        isFocusable = true
        isHapticFeedbackEnabled = false
    }

    // ------------------------------------------------------------------ lifecycle

    fun startLoop() {
        if (running || !loopEnabled) return
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopLoop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 0f else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = frameTimeNanos
        advance(dt)
        invalidate()
        if (mode == Mode.PAUSED) {
            running = false // a paused game needs no further frames; resume restarts the loop
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Advances everything by [dt] seconds. Public for deterministic tests. */
    fun advance(dt: Float) {
        clock += dt
        when (mode) {
            Mode.ATTRACT -> {
                attractZ += 5f * dt
                attractCycle += dt
            }
            Mode.PLAYING -> {
                world.update(dt)
                listener?.onTick((world.speed - GameConfig.V_MIN) / (GameConfig.V_MAX - GameConfig.V_MIN))
            }
            Mode.PAUSED -> return
            Mode.CRASHED -> {
                crashT += dt
                if (crashT >= CRASH_SEQUENCE && !crashNotified) {
                    crashNotified = true
                    listener?.onCrashSequenceDone()
                }
            }
        }
        fx.update(dt)
        renderer.updatePopups(dt)
        if (shake > 0f) shake = (shake - dt * 1.6f).coerceAtLeast(0f)
        if (flash > 0f) flash = (flash - dt * 3f).coerceAtLeast(0f)
    }

    // ------------------------------------------------------------------ control

    fun showAttract() {
        mode = Mode.ATTRACT
        attractZ = world.distance
        fx.clear()
        renderer.clearPopups()
        startLoop()
        invalidate()
    }

    fun startRun(seed: Long) {
        world.reset(seed)
        fx.clear()
        renderer.clearPopups()
        crashT = 0f
        crashNotified = false
        shake = 0f
        flash = 0f
        spin = 0f
        mode = Mode.PLAYING
        startLoop()
    }

    fun pauseGame() {
        if (mode != Mode.PLAYING) return
        mode = Mode.PAUSED
        invalidate()
    }

    fun resumeGame() {
        if (mode != Mode.PAUSED) return
        mode = Mode.PLAYING
        startLoop()
    }

    fun revive() {
        world.revive()
        crashT = 0f
        crashNotified = false
        spin = 0f
        mode = Mode.PLAYING
        renderer.addPopup(TXT_GO, world.playerX, 1.2f, 0xFF2EC4B6.toInt(), 30f)
        startLoop()
    }

    fun setInsets(top: Int, bottom: Int) {
        renderer.setInsets(top.toFloat(), bottom.toFloat())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        renderer.resize(w, h)
    }

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode != Mode.PLAYING) return mode != Mode.ATTRACT
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (id < MAX_POINTERS) {
                    downX[id] = e.getX(i)
                    downY[id] = e.getY(i)
                    swiped[id] = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (id >= MAX_POINTERS || swiped[id]) continue
                    val dx = e.getX(i) - downX[id]
                    val dy = e.getY(i) - downY[id]
                    if (abs(dx) > swipeThreshold && abs(dx) > abs(dy)) {
                        swiped[id] = true
                        world.requestLane(if (dx < 0) -1 else 1)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (id < MAX_POINTERS && !swiped[id]) {
                    world.requestLane(if (e.getX(i) < width / 2f) -1 else 1)
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> for (k in 0 until MAX_POINTERS) swiped[k] = true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------ world events

    override fun onLaneChange(dir: Int) {
        listener?.onFeedback(FB_LANE, 0)
    }

    override fun onCoin(x: Float, z: Float) {
        fx.emit(x, z, 8, 0.6f, 1.6f, 0.2f, 0.45f, 0.05f, 0f, 0xFFFFE082.toInt(), 0f, world.speed * 0.6f)
        renderer.addPopup(TXT_COIN, x, 0.7f, 0xFFFFD54A.toInt(), 18f)
        listener?.onFeedback(FB_COIN, 0)
    }

    override fun onShieldPickup(x: Float, z: Float) {
        fx.emit(x, z, 18, 0.8f, 2.2f, 0.3f, 0.6f, 0.06f, 0f, 0xFF4DD0E1.toInt(), 0f, world.speed * 0.6f)
        renderer.addPopup(TXT_SHIELD, x, 1.1f, 0xFF4DD0E1.toInt(), 26f)
        listener?.onFeedback(FB_SHIELD, 0)
    }

    override fun onShieldBreak(x: Float, z: Float) {
        fx.emit(x, z, 30, 1.5f, 4f, 0.3f, 0.7f, 0.06f, 0f, 0xFF80DEEA.toInt(), 0f, world.speed * 0.5f)
        fx.emit(x, z, 12, 0.5f, 1.5f, 0.4f, 0.8f, 0.12f, 0.2f, 0xCCECEFF1.toInt(), 0f, world.speed * 0.5f)
        shake = 0.5f
        flash = 0.35f
        renderer.addPopup(TXT_SHIELD_BREAK, world.playerX, 1.2f, 0xFF80DEEA.toInt(), 24f)
        listener?.onFeedback(FB_SHIELD_BREAK, 0)
    }

    override fun onOil(x: Float, z: Float) {
        fx.emit(x, z - 0.3f, 14, 0.4f, 1.3f, 0.3f, 0.6f, 0.06f, 0f, 0xEE1A1A1A.toInt(), 0f, 0f)
        renderer.addPopup(TXT_OIL, x, 1.0f, 0xFFB0BEC5.toInt(), 22f)
        listener?.onFeedback(FB_OIL, 0)
    }

    override fun onNearMiss(x: Float, z: Float, combo: Int, points: Int, closeCall: Boolean) {
        val label = nearMissLabel(combo, closeCall)
        renderer.addPopup(label, (world.playerX + x) / 2f, 0.6f, if (closeCall) 0xFFFF7A1A.toInt() else 0xFFFFFFFF.toInt(), 22f)
        fx.emit((world.playerX + x) / 2f, world.distance, 6, 1f, 2.4f, 0.15f, 0.35f, 0.03f, 0f, 0xFFFFFFFF.toInt(), 0f, -world.speed * 0.2f)
        listener?.onFeedback(FB_NEAR_MISS, combo)
    }

    override fun onCrash(x: Float, z: Float) {
        mode = Mode.CRASHED
        crashT = 0f
        crashNotified = false
        shake = 1f
        flash = 0.8f
        spin = if (world.lane.vx < 0f) -1f else 1f
        fx.emit(x, z + 0.4f, 40, 2f, 6f, 0.2f, 0.6f, 0.04f, 0f, 0xFFFFC107.toInt())
        fx.emit(x, z + 0.4f, 24, 1f, 4f, 0.3f, 0.8f, 0.05f, 0f, 0xFFFF5722.toInt())
        fx.emit(x, z + 0.3f, 18, 0.3f, 1.2f, 0.8f, 1.6f, 0.18f, 0.45f, 0xAA616161.toInt())
        fx.emit(x, z, 14, 1.5f, 3.5f, 0.5f, 1f, 0.05f, 0f, Renderer.PLAYER_COLOR)
        listener?.onCrash()
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        val f = frame
        val playing = mode != Mode.ATTRACT
        f.clock = clock
        if (playing) {
            f.camZ = world.distance
            f.cycle = world.time
            f.speed = world.speed
            f.playerX = world.playerX
            f.playerTilt = (world.lane.vx * 0.9f).coerceIn(-14f, 14f)
            f.shield = world.shieldTimer
            f.playerBlink = world.invulnTimer > 0f && world.shieldTimer <= 0f
            f.combo = world.combo
            f.coins = world.coins
            f.score = world.score
        } else {
            f.camZ = attractZ
            f.cycle = attractCycle
            f.speed = 5f
            f.playerX = 0f
            f.playerTilt = 0f
            f.shield = 0f
            f.playerBlink = false
            f.combo = 0
        }
        f.showHud = playing
        f.showPlayer = true
        val ct = (crashT / 0.6f).coerceIn(0f, 1f)
        f.playerSpin = if (mode == Mode.CRASHED) spin * 38f * (1f - (1f - ct) * (1f - ct)) else 0f
        f.shake = shake
        f.flash = flash
        renderer.draw(canvas, world, fx, f)
    }

    companion object {
        const val CRASH_SEQUENCE = 0.9f

        /** Tests disable the real vsync loop and step the game with [advance] instead. */
        @JvmStatic
        var loopEnabled = true
        private const val MAX_POINTERS = 10

        const val FB_LANE = 0
        const val FB_COIN = 1
        const val FB_SHIELD = 2
        const val FB_SHIELD_BREAK = 3
        const val FB_OIL = 4
        const val FB_NEAR_MISS = 5

        private const val TXT_COIN = "+10"
        private const val TXT_SHIELD = "SHIELD!"
        private const val TXT_SHIELD_BREAK = "SHIELD SAVED YOU"
        private const val TXT_OIL = "OIL!"
        private const val TXT_GO = "GO!"

        /** Pre-built labels: no string building at runtime. */
        private val NEAR_MISS_LABELS: Array<String> = Array(GameConfig.MAX_COMBO * 2) { i ->
            val combo = i / 2 + 1
            val close = i % 2 == 1
            val pts = com.endlessdrive.core.Scoring.nearMissPoints(combo, close)
            (if (close) "CLOSE CALL +" else "NEAR MISS +") + pts
        }

        fun nearMissLabel(combo: Int, close: Boolean): String =
            NEAR_MISS_LABELS[(combo.coerceIn(1, GameConfig.MAX_COMBO) - 1) * 2 + if (close) 1 else 0]
    }
}
