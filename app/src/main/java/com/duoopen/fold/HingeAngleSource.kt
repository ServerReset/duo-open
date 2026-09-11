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
 * Several devices expose more than one hinge-ish sensor, and which one streams a
 * real angle versus a coarse posture (0/90/180) varies. So every sensor whose
 * metadata says it is an angle sensor is registered, and the one that actually
 * produces the most readings wins (a coarse posture sensor only fires a couple
 * of times per fold). Readings are only taken from the chosen sensor, so a
 * coarse sensor can't interleave its stops into a streaming one.
 *
 * Only sensors with a real angular range (150°+) and a streaming reporting mode
 * are candidates, which rejects 0/1 state sensors and the coarse wake-up hinge.
 * Jitter is removed with the small-signal deadband + adaptive low-pass used by
 * `Nemoyuzx/android-duo`.
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    private val allSensors: List<Sensor> = sensorManager?.let(::accessibleSensors).orEmpty()

    /** Sensors that look like real angle sensors, best first (for the UI/report). */
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

    private var started = false
    private var powerSave = false
    private var lastEventUptime = 0L
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

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

    // Adaptive filter state (applied to the active sensor only).
    private var filtered: Float? = null
    private var previousRaw: Float? = null
    private var lastFilterUptime = 0L

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
            // Samsung's continuous folding_angle needs com.samsung.permission.SSENSOR
            // (signature|privileged); if it is not granted this throws. Catch it
            // instead of crashing and log it so the report shows the denial.
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

        // Reading count dominates: a streaming sensor out-scores a posture one
        // that fires twice a fold. Small spread bonus to break early ties.
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
                Log.i(TAG, "active sensor -> ${best.name} (count=$bestCount range=%.1f)".format(stats[key(best)]?.spread ?: 0f))
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
        lastAngle = smooth
        onAngle(smooth)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
        // Sparse posture sensors (0/90/180) report only a few times per fold, so
        // spread each change across the measured gap — never snap — or the
        // effect jumps. Streaming sensors keep the fast adaptive constant.
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
        // Samsung's real continuous folding angle (protected; may be denied on
        // register). Try it even though it declares a 0..1 range.
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

    /**
     * A sensor declaring a 0..1 range reports a normalized fraction; scale to
     * degrees. Standard angle sensors (range 150°+) are already degrees.
     */
    private fun scaleFor(s: Sensor, value: Float): Float {
        val smallRange = s.maximumRange.isFinite() && s.maximumRange in 0.1f..1.5f
        return if (smallRange && value <= 1.5f) 180f else 1f
    }

    private fun isFoldRelated(s: Sensor): Boolean {
        val text = (s.name + " " + s.stringType).lowercase()
        return text.contains("hinge") || text.contains("fold") || text.contains("posture")
    }

    private companion object {
        const val TAG = "DuoHinge"
        const val DEVICE_PRIVATE_BASE = 0x10000
        const val DEADBAND_DEG = 0.3f
        const val ACTIVE_GAP_MS = 1_200f
    }
}
