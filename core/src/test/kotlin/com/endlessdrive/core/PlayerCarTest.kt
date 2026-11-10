package com.endlessdrive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlayerCarTest {
    private val dt = 1f / 120f

    private fun response(speed: Float, steer: Float, seconds: Float): Float {
        val c = PlayerCar(); val i = Input()
        c.reset(0.0, 1, speed, i); c.v = speed
        i.steer = steer
        var t = 0f
        while (t < seconds) { c.update(dt, i, speed); c.v = speed; t += dt }
        return c.latVel
    }

    @Test
    fun steeringIsSmoothedAndLessTwitchyAtSpeed() {
        val slow = response(GameConfig.V0, 0.5f, 0.1f)
        val fast = response(GameConfig.V_MAX_PLAYER, 0.5f, 0.1f)
        assertTrue("smoothed: no instant jump", abs(slow) < 0.5f * GameConfig.LAT_MAX)
        assertTrue("less twitchy at speed: $fast < $slow", abs(fast) < abs(slow))
        // full input still reaches full authority at any speed
        assertEquals(GameConfig.LAT_MAX, response(GameConfig.V_MAX_PLAYER, 1f, 2f), 0.05f)
    }

    @Test
    fun steeringIsAnalogNotSnapping() {
        val c = PlayerCar(); val i = Input()
        c.reset(0.0, 1, 30f, i)
        i.steer = 0.2f
        repeat(30) { c.update(1f / 60f, i, 30f) }
        val mid = c.x
        assertTrue("car sits between lanes", mid > GameConfig.LANE1_X && mid < GameConfig.LANE1_X + 2f)
    }

    @Test
    fun boostHasCooldownAndBrakeSlows() {
        val c = PlayerCar(); val i = Input()
        c.reset(0.0, 1, 30f, i)
        i.boostPresses++
        c.update(dt, i, 30f)
        assertTrue(c.boosting)
        repeat((GameConfig.BOOST_TIME / dt).toInt() + 5) { c.update(dt, i, 30f) }
        assertTrue(!c.boosting)
        i.boostPresses++
        c.update(dt, i, 30f)
        assertTrue("cooldown blocks a second boost", !c.boosting)
        repeat((GameConfig.BOOST_COOLDOWN / dt).toInt()) { c.update(dt, i, 30f) }
        i.boostPresses++
        c.update(dt, i, 30f)
        assertTrue(c.boosting)
        i.brake = true
        repeat(600) { c.update(dt, i, 30f) }
        assertEquals(GameConfig.BRAKE_MIN_SPEED, c.v, 0.01f)
    }

    @Test
    fun speedNeverExceedsMax() {
        val c = PlayerCar(); val i = Input()
        c.reset(0.0, 1, GameConfig.V1, i)
        repeat(20) { i.boostPresses++; repeat(600) { c.update(dt, i, GameConfig.V1) } }
        assertTrue(c.v <= GameConfig.V_MAX_PLAYER)
    }
}
