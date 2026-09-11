package com.duoopen.fold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * Reports the foldable's hinge angle in degrees (0 = closed, 180 = flat).
 *
 * Sensor choice matches the original app: the platform `TYPE_HINGE_ANGLE`
 * sensor (continuous before wake-up), then any vendor sensor whose type or name
 * mentions "hinge". Values are passed straight through, and [eventGapMs]
 * reports how far apart readings typically are so the renderer can ease across
 * the gap — important because several hinges only emit a couple of readings per
 * fold rather than a continuous stream.
 *
 * The listener is left registered and untouched; re-registering to "poll" turns
 * out to reset some hinges' change detector and starves the feed.
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    /** Every hinge-capable sensor found, best candidate first. */
    val sensors: List<Sensor> = sensorManager?.let(::findHingeSensors).orEmpty()

    /** The preferred sensor. */
    val sensor: Sensor? = sensors.firstOrNull()

    /** Same as [sensor]; kept so the UI has one obvious "active" name. */
    val activeSensor: Sensor? get() = sensor

    /** Latest hinge angle. NaN until the first reading. */
    var lastAngle: Float = Float.NaN
        private set

    /** Latest unfiltered sensor value. NaN until the first reading. */
    var rawAngle: Float = Float.NaN
        private set

    /** Smoothed event rate over the last ~half second; 0 until events arrive. */
    var rateHz: Float = 0f
        private set

    /**
     * Smoothed spacing between readings, in ms. Small when the hinge streams,
     * large when it only reports a few times per fold.
     */
    var eventGapMs: Float = 0f
        private set

    private var started = false
    private var powerSave = false
    private var lastEventUptime = 0L
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

    /** Milliseconds since the last reading, or [Long.MAX_VALUE] if none yet. */
    fun lastEventAgeMs(): Long =
        if (lastEventUptime == 0L) Long.MAX_VALUE else SystemClock.uptimeMillis() - lastEventUptime

    /** One-line diagnostic for the UI: which sensor, how fast, and the raw value. */
    fun statusText(): String {
        val name = sensor?.name ?: "no sensor"
        val age = lastEventAgeMs()
        val ageText = if (age == Long.MAX_VALUE) "no events yet" else "last ${age}ms"
        val rate = if (rateHz > 0f) "%.0f Hz".format(rateHz) else "idle"
        val raw = if (rawAngle.isNaN()) "—" else "%.1f°".format(rawAngle)
        return "$name · $rate · raw $raw · $ageText"
    }

    fun start() {
        if (started) return
        started = true
        register()
    }

    /** Re-registers at a lower rate while the system is in Battery Saver. */
    fun setPowerSave(enabled: Boolean) {
        if (powerSave == enabled) return
        powerSave = enabled
        if (!started) return
        sensorManager?.unregisterListener(this)
        register()
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
    }

    private fun register() {
        val s = sensor ?: run {
            Log.w(TAG, "no hinge sensor found")
            return
        }
        val periodUs = if (powerSave) SLOW_PERIOD_US else FAST_PERIOD_US
        val ok = sensorManager?.registerListener(this, s, periodUs) == true
        Log.i(
            TAG,
            "hinge sensor=${s.name} type=${s.stringType} wakeUp=${s.isWakeUpSensor} " +
                "reportingMode=${s.reportingMode} minDelay=${s.minDelay}us " +
                "period=${periodUs}us powerSave=$powerSave ok=$ok",
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values.firstOrNull() ?: return
        if (!raw.isFinite()) return
        val now = SystemClock.uptimeMillis()

        if (lastEventUptime != 0L) {
            val gap = (now - lastEventUptime).toFloat()
            eventGapMs = if (eventGapMs <= 0f) gap else eventGapMs * 0.7f + gap * 0.3f
        }
        lastEventUptime = now
        rawAngle = raw
        lastAngle = raw
        tickRate(now)
        onAngle(raw)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun tickRate(now: Long) {
        if (rateWindowStart == 0L) rateWindowStart = now
        rateWindowCount++
        val elapsed = now - rateWindowStart
        if (elapsed >= 500L) {
            rateHz = rateWindowCount * 1000f / elapsed
            rateWindowStart = now
            rateWindowCount = 0
        }
    }

    /**
     * Platform sensors first (continuous before wake-up), then vendor sensors
     * whose type/name mentions "hinge". Duplicates are collapsed by name.
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
        /** Original 125 Hz registration; left alone so the hinge keeps streaming. */
        const val FAST_PERIOD_US = 8_000
        /** ~15 Hz under Battery Saver: plenty to follow a fold, far fewer wakeups. */
        const val SLOW_PERIOD_US = 66_000
    }
}
