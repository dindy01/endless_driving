package com.endlessdrive.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class MathTest {
    @Test
    fun roadIsContinuousGentleAndRecycled() {
        val road = RoadGeometry(7)
        val a = RoadPose(); val b = RoadPose()
        var s = 0.0
        road.ensure(0.0, 400.0)
        var maxCurv = 0.0
        while (s < 50_000.0) {
            road.ensure(s - 50, s + 400)
            road.pose(s, a); road.pose(s + 1.0, b)
            val step = hypot(b.x - a.x, b.z - a.z)
            assertEquals("centreline speed ~1 m per m at s=$s", 1.0, step, 0.02)
            assertTrue("hills are gentle", abs(a.grade) < 0.1f)
            maxCurv = maxOf(maxCurv, abs(road.curvature(s)))
            s += 37.0
        }
        assertTrue("curves are gentle (min radius ${1 / maxCurv} m)", 1 / maxCurv > 300)
    }

    @Test
    fun environmentCyclesThroughAllPhases() {
        val e = Environment()
        val seen = BooleanArray(4)
        var t = 0f
        while (t < Environment.PHASE * 4) { e.update(t); seen[e.phase] = true; t += 1f }
        assertTrue(seen.all { it })
        e.update(Environment.PHASE * 2 + 30f)
        assertTrue("night", e.night > 0.9f)
        e.update(Environment.PHASE * 3 + 30f)
        assertTrue("rain", e.rain > 0.9f)
    }

    @Test
    fun matricesAreSane() {
        val v = FloatArray(16); val p = FloatArray(16); val vp = FloatArray(16); val o = FloatArray(4)
        Mat4.lookAt(v, 0f, 0f, 0f, 0f, 0f, 10f)
        Mat4.perspective(p, 1f, 1.5f, 0.3f, 1000f)
        Mat4.multiply(vp, p, v)
        Mat4.transform(o, vp, 0f, 0f, 10f)
        assertEquals(0f, o[0] / o[3], 1e-4f)
        assertTrue("in front of camera", o[3] > 0f)
        // a point to the camera's right (R = (-1,0,0) when looking +Z) maps to +x on screen
        Mat4.transform(o, vp, -2f, 0f, 10f)
        assertTrue(o[0] / o[3] > 0f)
    }
}
