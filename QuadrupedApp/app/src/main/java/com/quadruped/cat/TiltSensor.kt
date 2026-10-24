package com.quadruped.cat

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Body pitch and roll from the phone's gravity sensor.
 *
 * Assumes the phone is mounted in landscape (top of the screen to the left, i.e. display
 * rotation 90) with the back camera facing forward. In device axes (x right, y up, z out of
 * the screen) that makes robot forward = -z, left = +y, up = +x. Android's gravity/accelerometer
 * values point toward "up", so tilt comes straight from the normalised vector.
 */
class TiltSensor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Nose-up pitch in radians, relative to the last calibration. */
    @Volatile var pitchUp = 0f; private set
    /** Left-side-up roll in radians, relative to the last calibration. */
    @Volatile var rollLeftUp = 0f; private set

    private var rawPitch = 0f
    private var rawRoll = 0f
    private var offPitch = 0f
    private var offRoll = 0f
    private var primed = false

    fun start() {
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sm.unregisterListener(this)

    /** Records the current attitude as "level". Do this with the robot standing on flat ground. */
    fun calibrate() {
        offPitch = rawPitch
        offRoll = rawRoll
        pitchUp = 0f
        rollLeftUp = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (mag < 1f) return
        val pitch = asin((-v[2] / mag).coerceIn(-1f, 1f))
        val roll = asin((v[1] / mag).coerceIn(-1f, 1f))
        // Low-pass filter to take the edge off walking vibration.
        val a = if (primed) 0.2f else 1f
        primed = true
        rawPitch += a * (pitch - rawPitch)
        rawRoll += a * (roll - rawRoll)
        pitchUp = rawPitch - offPitch
        rollLeftUp = rawRoll - offRoll
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
