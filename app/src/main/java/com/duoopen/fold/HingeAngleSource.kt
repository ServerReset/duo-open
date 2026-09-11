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
 * The Z Fold 7's public hinge sensor only reports 0 / 90 / 180 (its continuous
 * `folding_angle` sensor is signature-gated), so the raw value is passed
 * through a light deadband + adaptive low-pass to smooth the steps. An earlier
 * attempt drove the effect from the gyroscope; it could latch at a stale
 * mid-angle and freeze the overlay, so the gyro is deliberately not used here.
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    private val allSensors: List<Sensor> = sensorManager?.let(::accessibleSensors).orEmpty()

    /** Sensors that look like real angle sensors, best first (for the UI list). */
    val sensors: List<Sensor> = allSensors.filter(::isAngleCandidate)
        .sortedWith(
            compareBy(
                { if (it.type == Sensor.TYPE_HINGE_ANGLE) 0 else 1 },
                { if (it.isWakeUpSensor) 1 else 0 },
            ),
        )

    /** Preferred sensor (metadata order); the live choice is [activeSensor]. */
    val sensor: Sensor? = sensors.firstOrNull()
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    /** The sensor currently driving the effect; null until one reports. */
    var activeSensor: Sensor? = null
        private set

    /** Latest smoothed hinge angle. NaN until the first reading. */
    var lastAngle: Float = Float.NaN
        private set

    /** Latest unfiltered sensor value. NaN until the first reading. */
    var rawAngle: Float = Float.NaN
        private set

    /** Smoothed event rate over the last ~half second; 0 until events arrive. */
    var rateHz: Float = 0f
        private set

    /** Smoothed spacing between readings, in ms. */
    var eventGapMs: Float = 0f
        private set

    /** Kept for callers; the gyro estimator is no longer used. */
    val gyroActive: Boolean get() = false

    private var started = false
    private var powerSave = false
    private var lastEventUptime = 0L
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

    /** Last time the reported angle actually changed (not just a repeat event). */
    private var lastChangeUptime = 0L

    private class Stat {
        var count = 0
        var minVal = Float.MAX_VALUE
        var maxVal = -Float.MAX_VALUE
        var last = Float.NaN
        fun update(v: Float) {
            count++
            last = v
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        val spread: Float get() = if (maxVal >= minVal) maxVal - minVal else 0f
    }

    private val stats = HashMap<String, Stat>()

    // Light adaptive filter state (deadband + low-pass).
    private var filtered: Float? = null
    private var previousRaw: Float? = null
    private var lastFilterUptime = 0L

    /** Milliseconds since the last reading, or huge if none yet. */
    fun lastEventAgeMs(): Long =
        if (lastEventUptime == 0L) Long.MAX_VALUE else SystemClock.uptimeMillis() - lastEventUptime

    /** One-line diagnostic for the UI. */
    fun statusText(): String {
        val name = activeSensor?.name ?: sensor?.name ?: "no sensor"
        val age = lastEventAgeMs()
        val ageText = if (age == Long.MAX_VALUE) "no events yet" else "last ${age}ms"
        val rate = if (rateHz > 0f) "%.0f Hz".format(rateHz) else "idle"
        val raw = if (rawAngle.isNaN()) "—" else "%.1f°".format(rawAngle)
        return "$name · $rate · raw $raw · $ageText"
    }

    /** Full dump for the "Copy sensor report" button. */
    fun sensorReport(): String {
        val sb = StringBuilder("Duo Open hinge sensor report\n")
        sb.append("active=${activeSensor?.name ?: sensor?.name ?: "none"}\n")
        sb.append("rate=${"%.1f".format(rateHz)}Hz gap=${"%.0f".format(eventGapMs)}ms\n")
        sb.append("sensors:\n")
        val related = allSensors.filter(::isFoldRelated)
        if (related.isEmpty()) sb.append("- (none matched hinge/fold)\n")
        for (s in related) {
            val st = stats[key(s)]
            sb.append(
                "- name=${s.name} type=${s.type} stringType=${s.stringType} " +
                    "wakeUp=${s.isWakeUpSensor} mode=${s.reportingMode} " +
                    "maxRange=${s.maximumRange} minDelay=${s.minDelay}us " +
                    "count=${st?.count ?: 0} range=${"%.0f".format(st?.spread ?: 0f)} " +
                    "last=${st?.last ?: Float.NaN}\n",
            )
        }
        return sb.toString()
    }

    fun start() {
        if (started) return
        started = true
        register()
    }

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
        val sm = sensorManager ?: return
        val toRegister = sensors.ifEmpty { listOfNotNull(sensor) }
        if (toRegister.isEmpty()) {
            Log.w(TAG, "no usable hinge angle sensor found")
            return
        }
        val periodUs = if (powerSave) SensorManager.SENSOR_DELAY_UI else SensorManager.SENSOR_DELAY_GAME
        activeSensor = null
        filtered = null
        previousRaw = null
        lastFilterUptime = 0L
        for (s in toRegister) {
            // Samsung's folding_angle needs com.samsung.permission.SSENSOR
            // (signature|privileged); if not granted this throws. Catch it.
            val ok = try {
                sm.registerListener(this, s, periodUs)
            } catch (e: SecurityException) {
                Log.w(TAG, "register denied for ${s.name}: ${e.message}")
                false
            }
            Log.i(
                TAG,
                "candidate name=${s.name} type=${s.stringType} wakeUp=${s.isWakeUpSensor} " +
                    "reportingMode=${s.reportingMode} maxRange=${s.maximumRange} " +
                    "period=${periodUs}us powerSave=$powerSave ok=$ok",
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val rawValue = event.values.firstOrNull() ?: return
        // Samsung's folding_angle declares a 0..1 range but reports the angle
        // normalized; scale it to degrees. Standard sensors are already degrees.
        val scale = scaleFor(event.sensor, rawValue)
        val raw = rawValue * scale
        // Drop non-angles (state codes, radians) rather than showing garbage.
        if (!raw.isFinite() || raw !in 0f..180f) return
        val now = SystemClock.uptimeMillis()

        val stat = stats.getOrPut(key(event.sensor)) { Stat() }
        stat.update(raw)

        // Reading count dominates: a streaming sensor out-scores a posture one.
        var best: Sensor? = null
        var bestScore = Long.MIN_VALUE
        for (s in (sensors.ifEmpty { listOfNotNull(sensor) })) {
            val t = stats[key(s)] ?: continue
            val score = t.count.toLong() * 1000L + if (t.spread >= 5f) 1_000_000L else 0L
            if (score > bestScore) {
                bestScore = score
                best = s
            }
        }
        val current = activeSensor
        if (best != null && best != current) {
            val bestCount = stats[key(best)]?.count ?: 0
            val currentCount = current?.let { stats[key(it)]?.count } ?: 0
            if (current == null || bestCount > currentCount + 3) {
                activeSensor = best
                filtered = null
                previousRaw = null
                lastFilterUptime = 0L
                Log.i(TAG, "active sensor -> ${best.name}")
            }
        }
        if (event.sensor != activeSensor) return

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
        val changed = lastAngle.isNaN() || abs(smooth - lastAngle) >= CHANGE_EPSILON
        lastAngle = smooth
        // Only report a real change, so downstream movement timers reflect the
        // hinge actually moving — not repeated identical sensor events.
        if (changed) {
            lastChangeUptime = now
            onAngle(smooth)
        }
    }

    /** Milliseconds since the angle last changed, or huge if it never has. */
    fun millisSinceChange(): Long =
        if (lastChangeUptime == 0L) Long.MAX_VALUE
        else SystemClock.uptimeMillis() - lastChangeUptime

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Deadband (ignore sub-0.3° noise) plus an adaptive low-pass: heavy on slow
     * drift, ~12 ms on a real fold. Never snaps across a gap, so a coarse
     * sensor still animates instead of jumping.
     */
    private fun filter(raw: Float, now: Long): Float {
        val current = filtered
        val last = lastFilterUptime
        if (current == null || last == 0L || now < last) {
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
        val fast = (speed / 90f).coerceIn(0f, 1f)
        val adaptive = 65f + (12f - 65f) * fast
        val tauMillis = maxOf(adaptive, elapsed / 1.2f)
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

    private fun key(s: Sensor) = "${s.name}|${s.type}|${s.isWakeUpSensor}"

    /** A real hinge *angle* sensor: standard type, or a vendor sensor with an angle range. */
    private fun isAngleCandidate(s: Sensor): Boolean {
        val text = (s.name + " " + s.stringType).lowercase()
        if (text.contains("folding_angle")) return true
        val range = s.maximumRange
        if (!range.isFinite() || range !in 150f..360f) return false
        if (s.reportingMode != Sensor.REPORTING_MODE_CONTINUOUS &&
            s.reportingMode != Sensor.REPORTING_MODE_ON_CHANGE
        ) {
            return false
        }
        if (s.type == Sensor.TYPE_HINGE_ANGLE) return true
        if (s.type < DEVICE_PRIVATE_BASE) return false
        return (text.contains("hinge") || text.contains("fold")) && text.contains("angle")
    }

    private fun isFoldRelated(s: Sensor): Boolean {
        val text = (s.name + " " + s.stringType).lowercase()
        return text.contains("hinge") || text.contains("fold") || text.contains("posture")
    }

    private fun scaleFor(s: Sensor, value: Float): Float {
        val smallRange = s.maximumRange.isFinite() && s.maximumRange in 0.1f..1.5f
        return if (smallRange && value <= 1.5f) 180f else 1f
    }

    private companion object {
        const val TAG = "DuoHinge"
        const val DEVICE_PRIVATE_BASE = 0x10000
        const val DEADBAND_DEG = 0.3f
        const val CHANGE_EPSILON = 0.05f
        const val ACTIVE_GAP_MS = 1_200f
    }
}
