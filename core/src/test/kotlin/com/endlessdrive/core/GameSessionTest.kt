package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionTest {
    private class L : SessionListener {
        var crashes = 0; var overs = 0
        override fun onCrash() { crashes++ }
        override fun onGameOver() { overs++ }
    }

    private fun frames(s: GameSession, n: Int) = repeat(n) { s.frame(1f / 60f) }

    @Test
    fun runLifecycle() {
        val l = L()
        val s = GameSession(l)
        frames(s, 60)
        assertEquals(Mode.ATTRACT, s.mode)
        assertTrue("attract demo drives", s.world.player.s > 10.0)
        s.requestStart(42)
        frames(s, 1)
        assertEquals(Mode.PLAYING, s.mode)
        assertEquals(0L, s.world.score)
        frames(s, 120)
        s.requestPause(); frames(s, 1)
        assertEquals(Mode.PAUSED, s.mode)
        val d = s.world.distance
        frames(s, 120)
        assertEquals(d, s.world.distance, 0.0)
        s.requestResume(); frames(s, 1)
        assertEquals(Mode.PLAYING, s.mode)
        // force a crash
        s.world.suppressSpawningForTest()
        s.world.placeForTest(Kind.BARRIER, 1, 30.0)
        frames(s, 120)
        assertEquals(1, l.crashes)
        assertTrue(s.mode == Mode.CRASHING || s.mode == Mode.GAME_OVER)
        assertTrue("slow motion right after crash", s.timeScale < 1f || s.crashTimer > GameSession.SLOWMO_TIME)
        frames(s, 120)
        assertEquals(Mode.GAME_OVER, s.mode)
        assertEquals(1, l.overs)
        s.requestRevive(); frames(s, 1)
        assertEquals(Mode.PLAYING, s.mode)
        assertTrue(s.world.alive)
        s.requestStart(43)
        val t0 = System.nanoTime()
        frames(s, 1)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("retry took $ms ms", ms < 100)
        assertEquals(Mode.PLAYING, s.mode)
    }

    @Test
    fun commandsSentBetweenFramesAreAllAppliedInOrder() {
        val s = GameSession()
        s.requestStart(1)
        s.requestPause() // both before the game thread runs a frame
        frames(s, 1)
        assertEquals(Mode.PAUSED, s.mode)
        s.requestResume(); s.requestPause(); s.requestResume()
        frames(s, 1)
        assertEquals(Mode.PLAYING, s.mode)
    }
}
