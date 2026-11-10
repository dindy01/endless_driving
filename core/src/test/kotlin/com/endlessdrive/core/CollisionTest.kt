package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollisionTest {
    @Test
    fun aabbBasics() {
        assertTrue(Collision.overlaps(0f, 0f, 1f, 1f, 1.5f, 0f, 1f, 1f))
        assertFalse(Collision.overlaps(0f, 0f, 1f, 1f, 2.0f, 0f, 1f, 1f)) // touching edges = no hit
        assertFalse(Collision.overlaps(0f, 0f, 1f, 1f, 0f, 3f, 1f, 1f))
    }

    @Test
    fun sameLaneHitsAdjacentLaneDoesNot() {
        for (kind in 0 until ObstacleKind.COUNT) {
            assertTrue(Collision.playerHits(0f, 0f, kind, 0f, 0f))
            assertFalse(Collision.playerHits(0f, 0f, kind, GameConfig.LANE_W, 0f))
        }
    }

    private fun world(): World = World(1L).also { it.disableSpawningForTest() }

    private fun run(w: World, seconds: Float) {
        var t = 0f
        while (t < seconds && w.alive) { w.update(1f / 60f); t += 1f / 60f }
    }

    @Test
    fun obstacleInPlayerLaneEndsRun() {
        val w = world()
        w.placeObstacleForTest(ObstacleKind.BARRIER, 1, 5f)
        run(w, 2f)
        assertFalse(w.alive)
    }

    @Test
    fun shieldAbsorbsExactlyOneHit() {
        val w = world()
        w.placePickupForTest(PickupKind.SHIELD, 1, 2f)
        w.placeObstacleForTest(ObstacleKind.BARRIER, 1, 5f)
        w.placeObstacleForTest(ObstacleKind.BARRIER, 1, 9f)
        run(w, 0.5f)
        assertTrue(w.shieldTimer > 0f)
        run(w, 0.6f) // reach first barrier (~6.6 units travelled)
        assertTrue("shield should absorb first hit", w.alive)
        assertEquals(0f, w.shieldTimer, 0f)
        run(w, 2f)
        assertFalse("second hit must end the run", w.alive)
    }

    @Test
    fun shieldExpiresAfterFiveSeconds() {
        val w = world()
        w.placePickupForTest(PickupKind.SHIELD, 1, 1f)
        run(w, 0.3f)
        assertTrue(w.shieldTimer > 4.5f)
        run(w, 5.1f)
        assertEquals(0f, w.shieldTimer, 0f)
    }

    @Test
    fun oilLocksSteeringBriefly() {
        val w = world()
        w.placeObstacleForTest(ObstacleKind.OIL, 1, 3f)
        run(w, 0.5f) // drive into oil (~3 units)
        assertTrue(w.alive)
        assertTrue(w.lane.lockTimer > 0f)
        w.requestLane(+1)
        run(w, 0.05f)
        assertEquals("steering locked", 1, w.lane.lane)
        run(w, GameConfig.OIL_LOCK_TIME)
        w.requestLane(+1)
        run(w, 0.2f)
        assertEquals(2, w.lane.lane)
    }

    @Test
    fun reviveClearsRoadAndGrantsInvulnerability() {
        val w = world()
        w.placeObstacleForTest(ObstacleKind.BARRIER, 1, 3f)
        run(w, 2f)
        assertFalse(w.alive)
        w.revive()
        assertTrue(w.alive)
        assertTrue(w.invulnTimer > 0f)
        assertTrue(w.obstacles.none { it.active })
    }
}
