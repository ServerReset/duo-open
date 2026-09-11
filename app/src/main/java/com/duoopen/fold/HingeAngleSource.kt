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
 * It listens to *every* hinge-capable sensor at once — the platform
 * `TYPE_HINGE_ANGLE` (continuous preferred) plus any vendor sensor whose type
 * or name mentions "hinge" — and locks onto whichever actually delivers
 * readings. Some vendors expose several, and which one talks (or stays silent)
 * varies by model; trying them all is what makes it work across the Galaxy Z
 * Fold line instead of latching onto a silent sensor.
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

    /** Preferred sensor (first candidate), for labelling. */
    val sensor: Sensor? = sensors.firstOrNull()

    /** The candidate currently delivering events, if any. */
    var activeSensor: Sensor? = null
        private set

    /** Latest filtered (smoothed) hinge angle. NaN until the first reading. */
    var lastAngle: Float = Float.NaN
        private set

    /** Latest unfiltered sensor value. NaN until the first reading. */
    var rawAngle: Float = Float.NaN
        private set

    /** Smoothed event rate over the last ~half second; 0 until events arrive. */
    var rateHz: Float = 0f
        private set

    private val filter = AngleFilter()
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
        val name = activeSensor?.name ?: sensor?.name ?: "no sensor"
        val age = lastEventAgeMs()
        val ageText = if (age == Long.MAX_VALUE) "no events yet" else "last ${age}ms"
        val rate = if (rateHz > 0f) "%.0f Hz".format(rateHz) else "idle"
        val raw = if (rawAngle.isNaN()) "—" else "%.1f°".format(rawAngle)
        return "$name · $rate · raw $raw · $ageText"
    }

    fun start() {
        if (started) return
        started = true
        registerAll()
    }

    /**
     * Re-registers at a much lower rate while the system is in Battery Saver,
     * so the wallpaper still tracks a fold without the high-rate wakeups.
     */
    fun setPowerSave(enabled: Boolean) {
        if (powerSave == enabled) return
        powerSave = enabled
        if (!started) return
        sensorManager?.unregisterListener(this)
        registerAll()
    }

    private fun registerAll() {
        val sm = sensorManager ?: return
        if (sensors.isEmpty()) {
            Log.w(TAG, "no hinge sensors found")
            return
        }
        filter.reset()
        activeSensor = null
        lastEventUptime = 0L
        val target = if (powerSave) SLOW_PERIOD_US else FAST_PERIOD_US
        for (s in sensors) {
            val periodUs = if (s.minDelay > target) s.minDelay else target
            val ok = sm.registerListener(this, s, periodUs)
            Log.i(
                TAG,
                "registered hinge candidate name=${s.name} type=${s.stringType} " +
                    "wakeUp=${s.isWakeUpSensor} minDelay=${s.minDelay}us period=${periodUs}us ok=$ok",
            )
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = event.sensor
        val now = SystemClock.uptimeMillis()

        // Lock onto the first sensor that talks, but switch if it goes quiet for
        // a while and another candidate is still reporting.
        val current = activeSensor
        if (current == null || (s.name != current.name && now - lastEventUptime > SWITCH_TIMEOUT_MS)) {
            if (current?.name != s.name) Log.i(TAG, "active hinge sensor -> ${s.name}")
            activeSensor = s
            filter.reset()
        }
        if (s.name != activeSensor?.name) return

        val raw = event.values.firstOrNull() ?: return
        if (!raw.isFinite()) return

        lastEventUptime = now
        rawAngle = raw
        tickRate(now)

        val smooth = filter.filter(raw, event.timestamp)
        lastAngle = smooth
        onAngle(smooth)
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
        /** ~50 Hz is plenty for a hand-driven fold and far lighter than 125 Hz. */
        const val FAST_PERIOD_US = 20_000
        /** ~15 Hz under Battery Saver: plenty to follow a fold, far fewer wakeups. */
        const val SLOW_PERIOD_US = 66_000
        /** How long a silent active sensor is tolerated before another can take over. */
        const val SWITCH_TIMEOUT_MS = 1_500L
    }
}
