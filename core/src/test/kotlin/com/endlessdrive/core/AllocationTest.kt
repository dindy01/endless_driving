package com.endlessdrive.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/** The hot loop (session frame: simulation, traffic AI, spawner, particles) allocates nothing. */
class AllocationTest {
    @Test
    fun sessionFrameDoesNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().threadId()
        val s = GameSession()
        val rng = Rng(3)
        s.requestStart(7)
        fun frames(n: Int) {
            for (i in 0 until n) {
                if (i % 30 == 0) s.input.steer = rng.range(-1f, 1f)
                if (i % 200 == 0) s.input.boostPresses++
                s.input.brake = i % 500 < 40
                if (s.mode == Mode.GAME_OVER) s.requestRevive()
                s.frame(1f / 60f)
            }
        }
        frames(20_000)
        val before = bean.getThreadAllocatedBytes(tid)
        frames(60_000)
        val allocated = bean.getThreadAllocatedBytes(tid) - before
        println("Allocated during 60k session frames: $allocated bytes")
        assertTrue("hot loop allocated $allocated bytes", allocated < 4096)
    }
}
