package com.endlessdrive.core

import com.endlessdrive.core.fx.ParticleSystem

object Mode {
    const val ATTRACT = 0
    const val PLAYING = 1
    const val PAUSED = 2
    const val CRASHING = 3
    const val GAME_OVER = 4
}

/** Snapshot for the HUD (written on the game thread, read on the UI thread; primitives only). */
class HudState {
    @Volatile @JvmField var score = 0L
    @Volatile @JvmField var speedKmh = 0
    @Volatile @JvmField var boostReady = 1f
    @Volatile @JvmField var boosting = false
    @Volatile @JvmField var shield = 0f
    @Volatile @JvmField var combo = 0
    @Volatile @JvmField var comboTimer = 0f
    @Volatile @JvmField var popupSeq = 0
    @Volatile @JvmField var popupPoints = 0
    @Volatile @JvmField var popupKind = 0 // 0 near miss, 1 close call, 2 shield, 3 shield break, 4 coin
    @Volatile @JvmField var onShoulder = false
    @Volatile @JvmField var mode = Mode.ATTRACT
}

/** Game-thread callbacks into the platform layer (sound, haptics, UI transitions). */
interface SessionListener {
    fun onCrash() {}
    fun onGameOver() {}
    fun onFeedback(event: Int, value: Int) {}
}

/**
 * Owns the World and the run state machine. UI threads only *request* transitions; the game
 * thread applies them at the start of the next frame, so the simulation is single-threaded.
 */
class GameSession(var listener: SessionListener? = null) : GameEvents {
    val input = Input()
    val world = World(1L, this)
    val fx = ParticleSystem()
    val hud = HudState()

    @Volatile var mode = Mode.ATTRACT
        private set
    /** Real seconds since the crash (drives slow motion and the game-over delay). */
    var crashTimer = 0f; private set
    var shake = 0f; private set
    /** Simulation time scale (slow motion right after a crash). */
    var timeScale = 1f; private set
    /** Seconds since session start (attract) or run start: drives the weather/day cycle. */
    var envTime = 0f; private set
    var crashSpin = 0f; private set

    // Ordered command queue (UI thread -> game thread). Fixed size, no allocation.
    private val cmdQueue = IntArray(16)
    private val seedQueue = LongArray(16)
    private var cmdHead = 0
    private var cmdCount = 0
    private val lock = Any()

    private fun post(c: Int, seed: Long = 0L) {
        synchronized(lock) {
            if (cmdCount == cmdQueue.size) { // drop oldest
                cmdHead = (cmdHead + 1) % cmdQueue.size
                cmdCount--
            }
            val i = (cmdHead + cmdCount) % cmdQueue.size
            cmdQueue[i] = c
            seedQueue[i] = seed
            cmdCount++
        }
    }

    init {
        world.demo = true
    }

    fun requestStart(seed: Long) = post(CMD_START, seed)
    fun requestPause() = post(CMD_PAUSE)
    fun requestResume() = post(CMD_RESUME)
    fun requestRevive() = post(CMD_REVIVE)
    fun requestAttract() = post(CMD_ATTRACT, 0x2545F4914F6CDD1DL)

    /** Called once per rendered frame on the game thread. */
    fun frame(realDtIn: Float) {
        val realDt = realDtIn.coerceIn(0f, 0.05f)
        while (true) {
            var c: Int
            var seed: Long
            synchronized(lock) {
                if (cmdCount == 0) {
                    c = CMD_NONE; seed = 0L
                } else {
                    c = cmdQueue[cmdHead]; seed = seedQueue[cmdHead]
                    cmdHead = (cmdHead + 1) % cmdQueue.size
                    cmdCount--
                }
            }
            if (c == CMD_NONE) break
            applyCommand(c, seed)
        }
        hud.mode = mode
        when (mode) {
            Mode.ATTRACT -> {
                envTime += realDt
                world.update(realDt, input)
            }
            Mode.PLAYING -> {
                timeScale = 1f
                world.update(realDt, input)
                envTime = world.time
            }
            Mode.PAUSED -> return
            Mode.CRASHING, Mode.GAME_OVER -> {
                crashTimer += realDt
                timeScale = if (crashTimer < SLOWMO_TIME) 0.25f else 1f
                world.updateAfterCrash(realDt * timeScale)
                if (mode == Mode.CRASHING && crashTimer >= CRASH_SEQUENCE) {
                    mode = Mode.GAME_OVER
                    hud.mode = mode
                    listener?.onGameOver()
                }
            }
        }
        fx.update(realDt * (if (mode == Mode.CRASHING) timeScale.coerceAtLeast(0.4f) else 1f))
        if (shake > 0f) shake = (shake - realDt * 1.4f).coerceAtLeast(0f)
        writeHud()
    }

    private fun applyCommand(c: Int, seed: Long) {
        when (c) {
            CMD_START -> {
                world.demo = false
                world.reset(seed)
                fx.clear()
                crashTimer = 0f
                shake = 0f
                crashSpin = 0f
                timeScale = 1f
                envTime = 0f
                mode = Mode.PLAYING
            }
            CMD_PAUSE -> if (mode == Mode.PLAYING) mode = Mode.PAUSED
            CMD_RESUME -> if (mode == Mode.PAUSED) mode = Mode.PLAYING
            CMD_REVIVE -> if (mode == Mode.GAME_OVER || mode == Mode.CRASHING) {
                world.revive(input)
                crashTimer = 0f
                crashSpin = 0f
                timeScale = 1f
                mode = Mode.PLAYING
            }
            CMD_ATTRACT -> {
                world.demo = true
                world.reset(seed)
                fx.clear()
                mode = Mode.ATTRACT
            }
        }
    }

    private fun writeHud() {
        val w = world
        val h = hud
        h.score = w.score
        h.speedKmh = (w.player.v * GameConfig.KMH_PER_MS).toInt()
        h.boostReady = w.player.boostReady
        h.boosting = w.player.boosting
        h.shield = w.shieldTimer
        h.combo = w.combo
        h.comboTimer = w.comboTimer
        h.onShoulder = w.player.onShoulder
    }

    // ---- GameEvents ----
    override fun onCoin(s: Double, x: Float) {
        fx.emit(s, x, 1.0f, 10, 2f, 5f, 0.3f, 0.6f, 0.08f, 0f, 0xFFFFD54F.toInt(), world.player.v * 0.8f, 0.6f, 4f)
        popup(4, GameConfig.COIN_POINTS)
        listener?.onFeedback(FB_COIN, 0)
    }

    override fun onShieldPickup(s: Double, x: Float) {
        fx.emit(s, x, 1.0f, 24, 2f, 6f, 0.4f, 0.8f, 0.1f, 0f, 0xFF4DD0E1.toInt(), world.player.v * 0.8f, 0.6f, 2f)
        popup(2, 0)
        listener?.onFeedback(FB_SHIELD, 0)
    }

    override fun onShieldBreak(s: Double, x: Float) {
        fx.emit(s, x, 0.8f, 40, 3f, 9f, 0.4f, 0.9f, 0.08f, 0f, 0xFF80DEEA.toInt(), world.player.v * 0.6f)
        shake = 0.5f
        popup(3, 0)
        listener?.onFeedback(FB_SHIELD_BREAK, 0)
    }

    override fun onNearMiss(s: Double, x: Float, combo: Int, points: Int, closeCall: Boolean) {
        popup(if (closeCall) 1 else 0, points)
        listener?.onFeedback(FB_NEAR_MISS, combo)
    }

    override fun onBoost() {
        listener?.onFeedback(FB_BOOST, 0)
    }

    override fun onCrash(s: Double, x: Float) {
        mode = Mode.CRASHING
        hud.mode = mode
        crashTimer = 0f
        shake = 1f
        crashSpin = if (world.player.latVel < 0f) -1f else 1f
        fx.emit(s + 2.0, x, 0.6f, 60, 4f, 14f, 0.3f, 0.9f, 0.06f, 0f, 0xFFFFC107.toInt(), 6f, 0.7f, 9f)
        fx.emit(s + 2.0, x, 0.6f, 30, 3f, 9f, 0.4f, 1.0f, 0.07f, 0f, 0xFFFF5722.toInt(), 6f, 0.7f, 9f)
        fx.emit(s + 1.0, x, 0.8f, 30, 0.5f, 2.5f, 1.2f, 2.4f, 0.5f, 1.4f, 0x99555555.toInt(), 2f, 1f, -0.6f)
        fx.emit(s + 1.5, x, 0.5f, 24, 3f, 8f, 0.6f, 1.4f, 0.12f, 0f, 0xFFD32F2F.toInt(), 5f, 0.6f, 9f)
        listener?.onFeedback(FB_CRASH, 0)
        listener?.onCrash()
    }

    private fun popup(kind: Int, points: Int) {
        hud.popupKind = kind
        hud.popupPoints = points
        hud.popupSeq = hud.popupSeq + 1
    }

    companion object {
        const val SLOWMO_TIME = 0.9f
        const val CRASH_SEQUENCE = 1.3f

        const val FB_COIN = 1
        const val FB_SHIELD = 2
        const val FB_SHIELD_BREAK = 3
        const val FB_NEAR_MISS = 4
        const val FB_BOOST = 5
        const val FB_CRASH = 6

        private const val CMD_NONE = 0
        private const val CMD_START = 1
        private const val CMD_PAUSE = 2
        private const val CMD_RESUME = 3
        private const val CMD_REVIVE = 4
        private const val CMD_ATTRACT = 5
    }
}
