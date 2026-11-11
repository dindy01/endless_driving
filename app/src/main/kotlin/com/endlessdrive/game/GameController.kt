package com.endlessdrive.game

import com.endlessdrive.core.Ev
import com.endlessdrive.core.World

/**
 * Owns the simulation and steps it once per rendered frame on the render thread.
 * The UI thread only writes the volatile input / command fields; nothing here allocates per frame.
 */
class GameController {
    val world = World()

    // ---- inputs (written by the UI / sensor threads)
    @Volatile var steer = 0f
    @Volatile var brake = false
    @Volatile var nitro = false
    @Volatile var paused = false

    // ---- commands, consumed at the start of the next frame
    @Volatile private var cmdStart = false
    @Volatile private var cmdSeed = 1L
    @Volatile private var cmdRevive = false
    @Volatile private var cmdAttract = false

    interface Listener {
        /** Called on the stepping thread with the Ev.* bits raised this frame. */
        fun onGameEvents(events: Int, world: World)
    }

    @Volatile var listener: Listener? = null

    fun startRun(seed: Long) { cmdSeed = seed; cmdStart = true; paused = false }
    fun revive() { cmdRevive = true; paused = false }
    fun showAttract() { cmdAttract = true; paused = false }

    /** Advances the game by [dtReal] seconds (clamped; long hitches do not teleport the car). */
    fun frame(dtReal: Float) {
        if (cmdAttract) { cmdAttract = false; world.enterAttract(cmdSeed + 17) }
        if (cmdStart) { cmdStart = false; world.startRun(cmdSeed) }
        if (cmdRevive) { cmdRevive = false; world.revive() }
        if (paused) return
        var dt = dtReal
        if (dt > 0.1f) dt = 0.1f
        if (dt <= 0f) return
        // sub-step so a slow frame can never tunnel through an obstacle
        val steps = if (dt > 0.034f) 3 else if (dt > 0.02f) 2 else 1
        val h = dt / steps
        var events = 0
        for (i in 0 until steps) {
            world.update(h, steer, brake, nitro)
            events = events or world.events
            if (events and Ev.GAME_OVER != 0) break
        }
        if (events != 0) listener?.onGameEvents(events, world)
    }
}
