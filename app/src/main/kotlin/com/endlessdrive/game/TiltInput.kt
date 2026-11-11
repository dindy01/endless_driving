package com.endlessdrive.game

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/** Accelerometer steering for landscape: roll the phone like a steering wheel. Full lock at about 22 degrees. */
class TiltInput(context: Context, private val rotation: () -> Int, private val sink: (Float) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var filtered = 0f
    val available: Boolean get() = sensor != null

    fun start() {
        filtered = 0f
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        sink(steerFrom(e.values[0], e.values[1], rotation()))
    }

    /** Pure mapping (unit-tested): device-frame gravity + display rotation -> steering in [-1, 1]. */
    fun steerFrom(ax: Float, ay: Float, rotation: Int): Float {
        // Component of gravity along the screen's horizontal axis; positive when the right edge dips.
        val along = when (rotation) {
            Surface.ROTATION_90 -> ay
            Surface.ROTATION_270 -> -ay
            Surface.ROTATION_180 -> ax
            else -> -ax
        }
        filtered += (along - filtered) * 0.35f
        var s = filtered / FULL_LOCK
        val dead = 0.06f
        s = if (s > dead) (s - dead) / (1f - dead) else if (s < -dead) (s + dead) / (1f - dead) else 0f
        return s.coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val FULL_LOCK = 9.81f * 0.375f // sin(22 deg)
    }
}
