package com.example.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.model.TripodTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Monitors hardware Gyroscope and Accelerometer sensors in real-time to detect
 * whether the device is mounted on a tripod / stable surface or handheld.
 * Automatically unlocks full Astrophotography deep-sky exposure capabilities when stable.
 */
class TripodSensorMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _telemetry = MutableStateFlow(
        TripodTelemetry(
            isTripodStable = true, // Default true on devices/emulators without gyro disturbance
            angularVelocityRadSec = 0.002f,
            linearJitterMeterSec2 = 0.01f,
            stabilityPercentage = 98
        )
    )
    val telemetry: StateFlow<TripodTelemetry> = _telemetry.asStateFlow()

    private var smoothedGyroMag = 0.002f
    private var smoothedAccelDelta = 0.01f
    private var lastAccelMag = SensorManager.GRAVITY_EARTH
    private var stableSamplesCount = 12

    private var pitchDeg = 45f
    private var rollDeg = 0f

    fun startMonitoring() {
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]
                val magnitude = sqrt(wx * wx + wy * wy + wz * wz)
                smoothedGyroMag = smoothedGyroMag * 0.82f + magnitude * 0.18f
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = sqrt(ax * ax + ay * ay + az * az)
                val delta = kotlin.math.abs(mag - lastAccelMag)
                lastAccelMag = mag
                smoothedAccelDelta = smoothedAccelDelta * 0.80f + delta * 0.20f

                // Compute device horizon pitch & roll for astro framing assistance
                pitchDeg = Math.toDegrees(atan2(ay.toDouble(), az.toDouble())).toFloat()
                rollDeg = Math.toDegrees(atan2(-ax.toDouble(), sqrt((ay * ay + az * az).toDouble()))).toFloat()
            }
        }

        // Tripod stability threshold:
        // Angular velocity < 0.035 rad/s and linear jitter < 0.18 m/s^2
        val instantStable = smoothedGyroMag < 0.035f && smoothedAccelDelta < 0.18f
        if (instantStable) {
            stableSamplesCount = (stableSamplesCount + 1).coerceAtMost(20)
        } else {
            stableSamplesCount = (stableSamplesCount - 2).coerceAtLeast(0)
        }

        val isTripod = stableSamplesCount >= 8
        val gyroScore = (1f - (smoothedGyroMag / 0.25f)).coerceIn(0f, 1f)
        val accelScore = (1f - (smoothedAccelDelta / 1.2f)).coerceIn(0f, 1f)
        val stabilityPct = ((gyroScore * 0.65f + accelScore * 0.35f) * 100f).roundToInt().coerceIn(5, 100)

        _telemetry.value = TripodTelemetry(
            isTripodStable = isTripod,
            angularVelocityRadSec = smoothedGyroMag,
            linearJitterMeterSec2 = smoothedAccelDelta,
            stabilityPercentage = stabilityPct,
            pitchDegrees = pitchDeg,
            rollDegrees = rollDeg
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
