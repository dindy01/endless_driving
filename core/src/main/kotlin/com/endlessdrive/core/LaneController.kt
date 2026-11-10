package com.endlessdrive.core

import com.endlessdrive.core.GameConfig.INPUT_BUFFER_SIZE
import com.endlessdrive.core.GameConfig.INPUT_BUFFER_TTL
import com.endlessdrive.core.GameConfig.LANES
import com.endlessdrive.core.GameConfig.LANE_CHANGE_TIME

/**
 * Eased lane changes (~120 ms, ease-out cubic) with a small ring-buffer of inputs so rapid
 * taps are never dropped: a buffered input starts as soon as the current change is half done.
 * Inputs older than [INPUT_BUFFER_TTL] are discarded (e.g. while steering is locked by oil).
 */
class LaneController {
    var lane = 1
        private set
    var x = 0f
        private set
    /** Lateral velocity (units/s), used for body tilt. */
    var vx = 0f
        private set
    var lockTimer = 0f
        private set

    private var fromX = 0f
    private var toX = 0f
    private var progress = 1f

    private val qDir = IntArray(INPUT_BUFFER_SIZE)
    private val qTime = FloatArray(INPUT_BUFFER_SIZE)
    private var qHead = 0
    private var qCount = 0

    val isChanging: Boolean get() = progress < 1f
    val pendingInputs: Int get() = qCount

    fun reset(startLane: Int = 1) {
        lane = startLane
        x = GameConfig.laneX(startLane)
        fromX = x
        toX = x
        progress = 1f
        vx = 0f
        lockTimer = 0f
        qHead = 0
        qCount = 0
    }

    /** dir: -1 = left, +1 = right. */
    fun request(dir: Int, now: Float) {
        if (dir == 0) return
        if (qCount == INPUT_BUFFER_SIZE) { // drop oldest
            qHead = (qHead + 1) % INPUT_BUFFER_SIZE
            qCount--
        }
        val idx = (qHead + qCount) % INPUT_BUFFER_SIZE
        qDir[idx] = if (dir < 0) -1 else 1
        qTime[idx] = now
        qCount++
    }

    fun lock(seconds: Float) {
        if (seconds > lockTimer) lockTimer = seconds
    }

    /** Advances the controller. Returns the direction of a lane change started this tick, else 0. */
    fun update(dt: Float, now: Float): Int {
        if (lockTimer > 0f) lockTimer = (lockTimer - dt).coerceAtLeast(0f)
        while (qCount > 0 && now - qTime[qHead] > INPUT_BUFFER_TTL) pop()

        var started = 0
        while (lockTimer <= 0f && qCount > 0 && progress >= 0.5f && started == 0) {
            val dir = qDir[qHead]
            pop()
            val target = (lane + dir).coerceIn(0, LANES - 1)
            if (target != lane) {
                fromX = x
                toX = GameConfig.laneX(target)
                lane = target
                progress = 0f
                started = dir
            }
        }

        val oldX = x
        if (progress < 1f) {
            progress = (progress + dt / LANE_CHANGE_TIME).coerceAtMost(1f)
            x = fromX + (toX - fromX) * easeOutCubic(progress)
        }
        vx = if (dt > 0f) (x - oldX) / dt else 0f
        return started
    }

    private fun pop() {
        qHead = (qHead + 1) % INPUT_BUFFER_SIZE
        qCount--
    }

    companion object {
        fun easeOutCubic(t: Float): Float {
            val u = 1f - t
            return 1f - u * u * u
        }
    }
}
