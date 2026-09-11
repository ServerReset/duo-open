package com.duoopen.fold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.exp

/**
 * Reports the foldable's hinge angle in degrees (0 = closed, 180 = flat).
 *
 * Selection follows the implementations that are known to work on Samsung
 * (`sururu-k/HingeNotifier` for the Galaxy Z Fold 4 and `Nemoyuzx/android-duo`):
 * use Android's standard `TYPE_HINGE_ANGLE`, prefer the non-wake-up variant, and
 * register just that one sensor at `SENSOR_DELAY_GAME`. Vendor sensors are only
 * accepted when their range really is an angle (150°+) — that rejects the
 * 0/1 state sensors and the coarse wake-up sensor that only reports 0/90/180.
 * Registering every hinge sensor at once is what previously latched onto the
 * coarse one.
 *
 * Jitter is removed with the small-signal deadband + adaptive low-pass from
 * `android-duo`: heavy smoothing on slow drift, ~12 ms response on a real fold.
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    private val allSensors: List<Sensor> = sensorManager?.let(::accessibleSensors).orEmpty()

    /** Every sensor that looks like a real angle sensor, best first (for the UI list). */
    val sensors: List<Sensor> = allSensors.filter(::isAngleCandidate)
        .sortedWith(
            compareBy(
                { if (it.type == Sensor.TYPE_HINGE_ANGLE) 0 else 1 },
                { if (it.isWakeUpSensor) 1 else 0 },
            ),
        )

    /**
     * The one sensor we read. Standard non-wake-up first, then the platform
     * default, then the best vendor candidate.
     */
    val sensor: Sensor? = sensors.firstOrNull()
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    /** Same as [sensor]; kept so the UI has one obvious "active" name. */
    val activeSensor: Sensor? get() = sensor

    /** Latest smoothed hinge angle. NaN until the first reading. */
    var lastAngle: Float = Float.NaN
        private set

    /** Latest unfiltered sensor value. NaN until the first reading. */
    var rawAngle: Float = Float.NaN
        private set

    /** Smoothed event rate over the last ~half second; 0 until events arrive. */
    var rateHz: Float = 0f
        private set

    /**
     * Smoothed spacing between readings, in ms. Kept for the renderers; small
     * whenever the hinge streams.
     */
    var eventGapMs: Float = 0f
        private set

    private var started = false
    private var powerSave = false
    private var lastEventUptime = 0L
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

    // Adaptive filter state.
    private var filtered: Float? = null
    private var previousRaw: Float? = null
    private var lastFilterUptime = 0L

    /** Milliseconds since the last reading, or huge if none yet. */
    fun lastEventAgeMs(): Long =
        if (lastEventUptime == 0L) Long.MAX_VALUE else SystemClock.uptimeMillis() - lastEventUptime

    /** One-line diagnostic for the UI. */
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
            Log.w(TAG, "no usable hinge angle sensor found")
            return
        }
        val periodUs = if (powerSave) {
            SensorManager.SENSOR_DELAY_UI
        } else {
            SensorManager.SENSOR_DELAY_GAME
        }
        filtered = null
        previousRaw = null
        lastFilterUptime = 0L
        val ok = sensorManager?.registerListener(this, s, periodUs) == true
        Log.i(
            TAG,
            "hinge sensor=${s.name} type=${s.stringType} wakeUp=${s.isWakeUpSensor} " +
                "reportingMode=${s.reportingMode} maxRange=${s.maximumRange} " +
                "period=${periodUs}us powerSave=$powerSave ok=$ok",
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor != sensor) return
        val raw = event.values.firstOrNull() ?: return
        // Drop non-angles (state codes, radians) rather than showing garbage.
        if (!raw.isFinite() || raw !in 0f..180f) return
        val now = SystemClock.uptimeMillis()

        if (lastEventUptime != 0L) {
            val gap = (now - lastEventUptime).toFloat()
            eventGapMs = if (gap > ACTIVE_GAP_MS) 0f
            else if (eventGapMs <= 0f) gap
            else eventGapMs * 0.7f + gap * 0.3f
        }
        lastEventUptime = now
        rawAngle = raw
        tickRate(now)

        val smooth = filter(raw, now)
        lastAngle = smooth
        onAngle(smooth)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Deadband (ignore sub-0.3° noise) plus an adaptive low-pass: slow drift is
     * smoothed over ~65 ms, a deliberate fast fold responds in ~12 ms.
     */
    private fun filter(raw: Float, now: Long): Float {
        val current = filtered
        val last = lastFilterUptime
        if (current == null || last == 0L) {
            filtered = raw
            previousRaw = raw
            lastFilterUptime = now
            return raw
        }
        if (now < last) {
            filtered = raw
            previousRaw = raw
            lastFilterUptime = now
            return raw
        }
        val elapsed = (now - last).coerceAtLeast(1L)
        val speed = abs(raw - (previousRaw ?: raw)) * 1000f / elapsed
        previousRaw = raw
        lastFilterUptime = now

        if (abs(raw - current) <= DEADBAND_DEG) return current
        if (elapsed > 250L) {
            filtered = raw
            return raw
        }
        val fast = (speed / 90f).coerceIn(0f, 1f)
        val tauMillis = 65f + (12f - 65f) * fast
        val alpha = 1f - exp(-elapsed.toFloat() / tauMillis)
        val next = current + alpha * (raw - current)
        filtered = next
        return next
    }

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

    private fun accessibleSensors(sm: SensorManager): List<Sensor> = try {
        sm.getSensorList(Sensor.TYPE_ALL)
    } catch (_: SecurityException) {
        emptyList()
    }

    /**
     * A real hinge *angle* sensor: standard type, or a vendor sensor (private
     * type space) whose name says hinge/fold + angle, with a range that really
     * is an angle and a streaming reporting mode.
     */
    private fun isAngleCandidate(s: Sensor): Boolean {
        val range = s.maximumRange
        if (!range.isFinite() || range !in 150f..360f) return false
        if (s.reportingMode != Sensor.REPORTING_MODE_CONTINUOUS &&
            s.reportingMode != Sensor.REPORTING_MODE_ON_CHANGE
        ) {
            return false
        }
        if (s.type == Sensor.TYPE_HINGE_ANGLE) return true
        if (s.type < DEVICE_PRIVATE_BASE) return false
        val text = (s.name + " " + s.stringType).lowercase()
        val mentionsAngle = text.contains("hinge") || text.contains("fold")
        return mentionsAngle && text.contains("angle")
    }

    private companion object {
        const val TAG = "DuoHinge"
        const val DEVICE_PRIVATE_BASE = 0x10000
        const val DEADBAND_DEG = 0.3f
        /** Spacings above this are idle pauses, not fold motion. */
        const val ACTIVE_GAP_MS = 1_200f
    }
}
