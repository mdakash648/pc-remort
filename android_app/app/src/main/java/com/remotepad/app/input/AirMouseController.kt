package com.remotepad.app.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 3D Air Mouse powered by the phone's gyroscope + gravity sensor.
 *
 * Projects angular velocity onto the true vertical gravity axis so panning the
 * phone left/right moves the cursor horizontally regardless of whether the user
 * holds the phone flat (remote-style), tilted at 45 deg, or upright.
 */
class AirMouseController(
    context: Context,
    private val onDelta: (dx: Float, dy: Float) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val isAvailable: Boolean get() = gyroSensor != null

    @Volatile
    private var running = false

    @Volatile
    var speedMultiplier: Float = 1.2f

    private var lastGyroTimestampNs: Long = 0L

    // Normalized gravity vector (defaults to phone tilted ~45 deg toward user)
    private var gx = 0f
    private var gy = 0.707f
    private var gz = 0.707f

    fun start(): Boolean {
        val manager = sensorManager ?: return false
        val gyro = gyroSensor ?: return false
        if (running) return true

        lastGyroTimestampNs = 0L
        running = true
        manager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        gravitySensor?.let { grav ->
            manager.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME)
        }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        lastGyroTimestampNs = 0L
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val norm = sqrt(x * x + y * y + z * z)
                if (norm > 1.0f) {
                    // Low-pass filter so sudden linear shakes don't perturb the vertical reference
                    val alpha = if (event.sensor.type == Sensor.TYPE_GRAVITY) 0.5f else 0.15f
                    gx += alpha * (x / norm - gx)
                    gy += alpha * (y / norm - gy)
                    gz += alpha * (z / norm - gz)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val ts = event.timestamp
                if (lastGyroTimestampNs == 0L) {
                    lastGyroTimestampNs = ts
                    return
                }
                val dt = ((ts - lastGyroTimestampNs) * 1e-9f).coerceIn(0.001f, 0.05f)
                lastGyroTimestampNs = ts

                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]

                // World-vertical yaw rate (positive when rotating right)
                val gNorm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.001f)
                val yawRate = -(wx * gx + wy * gy + wz * gz) / gNorm
                // Pitch rate around screen X axis (tilting top up -> move cursor up / negative dy)
                val pitchRate = -wx

                val filteredYaw = applyDeadZone(yawRate, DEAD_ZONE_RAD_S)
                val filteredPitch = applyDeadZone(pitchRate, DEAD_ZONE_RAD_S)
                if (filteredYaw == 0f && filteredPitch == 0f) return

                val gain = BASE_GAIN * speedMultiplier * dt
                onDelta(filteredYaw * gain, filteredPitch * gain)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyDeadZone(value: Float, deadZone: Float): Float {
        val mag = abs(value)
        if (mag <= deadZone) return 0f
        return if (value > 0f) mag - deadZone else -(mag - deadZone)
    }

    companion object {
        private const val DEAD_ZONE_RAD_S = 0.025f
        private const val BASE_GAIN = 950f
    }
}
