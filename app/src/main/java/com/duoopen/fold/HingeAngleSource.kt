package com.duoopen.fold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Reports the foldable's hinge angle in degrees (0 = closed, 180 = flat).
 *
 * Reads the platform `TYPE_HINGE_ANGLE` sensor, preferring the continuous
 * (non-wake-up) instance, and falls back to any vendor sensor whose type or
 * name mentions "hinge". Every hinge-capable sensor is collected and logged so
 * it's obvious on a new device (Galaxy Z Fold, Pixel Fold, …) which one is
 * being used.
 *
 * The raw stream is passed through an adaptive [AngleFilter]. Several vendors —
 * Galaxy Z Fold included — report the angle in coarse steps plus a little
 * noise; filtering turns that staircase into a continuous, live curve without
 * adding perceptible lag while the hinge actually moves.
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    /** Every hinge-capable sensor found, best candidate first. */
    val sensors: List<Sensor> = sensorManager?.let(::findHingeSensors).orEmpty()

    val sensor: Sensor? = sensors.firstOrNull()

    /** Latest filtered (smoothed) hinge angle. NaN until the first reading. */
    var lastAngle: Float = Float.NaN
        private set

    /** Latest unfiltered sensor value. NaN until the first reading. */
    var rawAngle: Float = Float.NaN
        private set

    private val filter = AngleFilter()
    private var started = false

    fun start() {
        val s = sensor ?: return
        if (started) return
        started = true
        filter.reset()
        val periodUs = if (s.minDelay > FAST_PERIOD_US) s.minDelay else FAST_PERIOD_US
        val registered = sensorManager?.registerListener(this, s, periodUs) == true ||
            sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME) == true
        Log.i(
            TAG,
            "hinge sensor=${s.name} type=${s.stringType} " +
                "wakeUp=${s.isWakeUpSensor} minDelay=${s.minDelay}us period=${periodUs}us registered=$registered",
        )
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values.firstOrNull() ?: return
        if (!raw.isFinite()) return
        rawAngle = raw
        val smooth = filter.filter(raw, event.timestamp)
        lastAngle = smooth
        onAngle(smooth)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Ranked so a continuous platform sensor wins over a wake-up one, then a
     * matching vendor sensor, then anything wake-up. Duplicates (the same
     * sensor surfaced twice) are collapsed.
     */
    private fun findHingeSensors(sm: SensorManager): List<Sensor> {
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        val platform = all.filter { it.type == Sensor.TYPE_HINGE_ANGLE }
        val vendor = all.filter { s ->
            s.type != Sensor.TYPE_HINGE_ANGLE &&
                (s.stringType.contains("hinge", ignoreCase = true) ||
                    s.name.contains("hinge", ignoreCase = true))
        }
        return (platform.sortedBy { it.isWakeUpSensor } + vendor.sortedBy { it.isWakeUpSensor })
            .distinctBy { it.name }
    }

    private companion object {
        const val TAG = "DuoHinge"
        /** ~125 Hz ceiling; the sensor only reports on change anyway. */
        const val FAST_PERIOD_US = 8_000
    }
}
