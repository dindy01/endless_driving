package com.endlessdrive.core

import com.endlessdrive.core.fx.ParticleSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/** Verifies the simulation hot loop allocates nothing (HotSpot per-thread allocation counter). */
class AllocationTest {
    @Test
    fun worldUpdateDoesNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().threadId()
        val w = World(42L)
        val fx = ParticleSystem()
        val rng = Rng(1)
        fun frames(n: Int) {
            for (i in 0 until n) {
                if (i % 20 == 0) w.requestLane(if (rng.nextFloat() < 0.5f) -1 else 1)
                if (!w.alive) w.revive()
                w.update(1f / 60f)
                if (i % 30 == 0) fx.emit(0f, w.distance, 12, 1f, 3f, 0.3f, 0.8f, 0.1f, 0.2f, -1)
                fx.update(1f / 60f)
            }
        }
        frames(20_000) // warm-up / JIT
        val before = bean.getThreadAllocatedBytes(tid)
        frames(60_000) // ~16 minutes of gameplay
        val allocated = bean.getThreadAllocatedBytes(tid) - before
        println("Allocated during 60k frames: $allocated bytes; dropped spawns=${w.droppedSpawns}")
        // tolerance for the measurement call itself
        assertTrue("hot loop allocated $allocated bytes", allocated < 4096)
        assertEquals(0, w.droppedSpawns)
    }

    @Test
    fun resetIsFastForInstantRetry() {
        val w = World(1L)
        repeat(1000) { w.update(1f / 60f) }
        val t0 = System.nanoTime()
        w.reset(2L)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("reset took $ms ms", ms < 50)
        assertTrue(w.alive)
        assertEquals(0L, w.score)
    }
}
