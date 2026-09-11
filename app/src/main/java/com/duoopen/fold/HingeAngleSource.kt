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
 * Every hinge-capable sensor is registered at once and the one that actually
 * streams a range of angles wins. Some devices expose several — a platform
 * `TYPE_HINGE_ANGLE` that only reports a couple of fixed stops, plus a vendor
 * "hinge" sensor with the real angle (or vice-versa). Picking a single sensor
 * up front can therefore latch onto the wrong one; this observes them all,
 * scores each on how many readings it produces and how wide a range it covers,
 * and switches to the best. The choice is logged and shown in the UI.
 *
 * Values are passed straight through, and [eventGapMs] reports the spacing
 * between active readings so a renderer can ease across a sparse feed.
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    /** Every hinge-capable sensor found. */
    val sensors: List<Sensor> = sensorManager?.let(::findHingeSensors).orEmpty()

    /** Preferred sensor, purely as a non-null "is there one" signal. */
    val sensor: Sensor? = sensors.firstOrNull()

    /** The sensor currently driving the effect; null until one reports. */
    var activeSensor: Sensor? = null
        private set

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
     * Smoothed spacing between active readings, in ms. Small when the hinge
     * streams, large when it only reports a few times per fold.
     */
    var eventGapMs: Float = 0f
        private set

    private var started = false
    private var powerSave = false
    private var lastEventUptime = 0L
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

    private class Stat {
        var count = 0
        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE
        fun update(v: Float) {
            count++
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        val spread: Float get() = if (maxVal >= minVal) maxVal - minVal else 0f
    }

    private val stats = HashMap<String, Stat>()

    /** Milliseconds since the last active reading, or huge if none yet. */
    fun lastEventAgeMs(): Long =
        if (lastEventUptime == 0L) Long.MAX_VALUE else SystemClock.uptimeMillis() - lastEventUptime

    /** One-line diagnostic for the UI: which sensor, how fast, and the raw value. */
    fun statusText(): String {
        val name = activeSensor?.name ?: sensor?.name ?: "no sensor"
        val age = lastEventAgeMs()
        val ageText = if (age == Long.MAX_VALUE) "no events yet" else "last ${age}ms"
        val rate = if (rateHz > 0f) "%.0f Hz".format(rateHz) else "idle"
        val raw = if (rawAngle.isNaN()) "—" else "%.1f°".format(rawAngle)
        val spread = activeSensor?.let { stats[it.name]?.spread }
        val spreadText = if (spread != null) " · range %.0f°".format(spread) else ""
        return "$name · $rate · raw $raw · $ageText$spreadText"
    }

    fun start() {
        if (started) return
        started = true
        registerAll()
    }

    /** Re-registers at a lower rate while the system is in Battery Saver. */
    fun setPowerSave(enabled: Boolean) {
        if (powerSave == enabled) return
        powerSave = enabled
        if (!started) return
        sensorManager?.unregisterListener(this)
        registerAll()
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
    }

    private fun registerAll() {
        val sm = sensorManager ?: return
        if (sensors.isEmpty()) {
            Log.w(TAG, "no hinge sensors found")
            return
        }
        for (s in sensors) {
            val periodUs = if (powerSave) SLOW_PERIOD_US else FAST_PERIOD_US
            val ok = sm.registerListener(this, s, periodUs)
            Log.i(
                TAG,
                "candidate name=${s.name} type=${s.stringType} wakeUp=${s.isWakeUpSensor} " +
                    "reportingMode=${s.reportingMode} minDelay=${s.minDelay}us " +
                    "period=${periodUs}us powerSave=$powerSave ok=$ok",
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values.firstOrNull() ?: return
        if (!raw.isFinite()) return
        val now = SystemClock.uptimeMillis()

        val stat = stats.getOrPut(event.sensor.name) { Stat() }
        stat.update(raw)

        // Score: readings matter most; a sensor that actually sweeps a range
        // beats one stuck on a couple of stops. Switch only with hysteresis.
        var best: Sensor? = null
        var bestScore = Long.MIN_VALUE
        for (s in sensors) {
            val t = stats[s.name] ?: continue
            val score = t.count.toLong() * 1000L + if (t.spread >= 5f) 1_000_000L else 0L
            if (score > bestScore) {
                bestScore = score
                best = s
            }
        }
        val current = activeSensor
        if (best != null && best.name != current?.name) {
            val bestCount = stats[best.name]?.count ?: 0
            val currentCount = current?.let { stats[it.name]?.count } ?: 0
            if (bestCount > currentCount + 2) {
                activeSensor = best
                Log.i(
                    TAG,
                    "active hinge sensor -> ${best.name} " +
                        "(count=$bestCount spread=%.1f°)".format(stats[best.name]?.spread ?: 0f),
                )
            }
        }
        if (event.sensor.name != activeSensor?.name) return

        if (lastEventUptime != 0L) {
            val gap = (now - lastEventUptime).toFloat()
            eventGapMs = when {
                // A real idle pause: forget it, so the next move starts fresh
                // instead of easing for a second.
                gap > ACTIVE_GAP_MS -> 0f
                eventGapMs <= 0f -> gap
                else -> eventGapMs * 0.7f + gap * 0.3f
            }
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

    /** Platform sensors first (continuous before wake-up), then vendor hinges. */
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
        /** Spacings above this are idle pauses, not fold motion. */
        const val ACTIVE_GAP_MS = 1_200f
    }
}
