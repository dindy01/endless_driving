package com.endlessdrive.game

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.endlessdrive.core.Input
import kotlin.math.atan2

/**
 * Accelerometer steering: rotate the phone like a steering wheel (rotation about the screen's
 * normal). Accounts for both landscape directions, low-pass filtered, small dead zone, full lock
 * at ~28 degrees.
 */
class TiltSteering(private val context: Context, private val input: Input) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var fx = 0f
    private var fy = 9.8f
    var active = false; private set
    val available: Boolean get() = sensor != null

    fun start() {
        if (active || sensor == null) return
        active = sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) == true
    }

    fun stop() {
        if (!active) return
        sm?.unregisterListener(this)
        active = false
        input.steer = 0f
    }

    private fun rotation(): Int = try {
        if (Build.VERSION.SDK_INT >= 30) context.display?.rotation ?: Surface.ROTATION_90
        else @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    } catch (_: Exception) {
        Surface.ROTATION_90
    }

    override fun onSensorChanged(e: SensorEvent) {
        val ax = e.values[0]; val ay = e.values[1]
        // "up" vector in screen coordinates
        val sx: Float; val sy: Float
        when (rotation()) {
            Surface.ROTATION_270 -> { sx = ay; sy = -ax }
            Surface.ROTATION_180 -> { sx = ax; sy = -ay }
            Surface.ROTATION_0 -> { sx = -ax; sy = ay }
            else -> { sx = -ay; sy = ax } // ROTATION_90
        }
        val k = 0.25f
        fx += (sx - fx) * k
        fy += (sy - fy) * k
        val angle = Math.toDegrees(atan2(-fx, fy).toDouble()).toFloat()
        val dead = 2.5f
        val a = if (angle > dead) angle - dead else if (angle < -dead) angle + dead else 0f
        input.steer = (a / 26f).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Pure mapping used by tests: screen-up vector -> steer. */
    companion object {
        fun steerFor(sx: Float, sy: Float): Float {
            val angle = Math.toDegrees(atan2(-sx, sy).toDouble()).toFloat()
            val a = if (angle > 2.5f) angle - 2.5f else if (angle < -2.5f) angle + 2.5f else 0f
            return (a / 26f).coerceIn(-1f, 1f)
        }
    }
}
