package com.duoopen.fold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Reports the foldable's hinge angle in degrees (0 = closed, 180 = flat).
 *
 * Uses the platform `TYPE_HINGE_ANGLE` sensor (non-wake-up preferred), falling
 * back to any vendor sensor whose name/type mentions "hinge". It's an
 * on-change sensor, so registering delivers the current angle immediately and
 * then only fires while the hinge actually moves.
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    val sensor: Sensor? = sensorManager?.let { sm ->
        sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, false)
            ?: sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
            ?: sm.getSensorList(Sensor.TYPE_ALL).firstOrNull {
                it.stringType.contains("hinge", ignoreCase = true) ||
                    it.name.contains("hinge", ignoreCase = true)
            }
    }

    var lastAngle: Float = Float.NaN
        private set

    private var started = false

    fun start() {
        val s = sensor ?: return
        if (started) return
        started = true
        sensorManager.registerListener(this, s, SAMPLING_PERIOD_US)
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values.firstOrNull() ?: return
        lastAngle = angle
        onAngle(angle)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** ~125 Hz ceiling; the sensor only reports on change anyway. */
        const val SAMPLING_PERIOD_US = 8_000
    }
}
