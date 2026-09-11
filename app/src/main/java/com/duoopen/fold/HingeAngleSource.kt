package com.duoopen.fold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Reports the foldable's hinge angle in degrees (0 = closed, 180 = flat),
 * passing the raw sensor value straight through so the effect tracks the hinge
 * in real time — no smoothing or lag.
 *
 * Sensor choice matches the original app: the platform `TYPE_HINGE_ANGLE`
 * sensor (continuous before wake-up), then any vendor sensor whose type or name
 * mentions "hinge". A slow keep-alive re-registers the listener if readings go
 * quiet, which forces the HAL to hand back the current angle on the quirky
 * hinges that report once and then stay silent — that's what keeps the display
 * live instead of only updating when the app is reopened.
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /** Every hinge-capable sensor found, best candidate first. */
    val sensors: List<Sensor> = sensorManager?.let(::findHingeSensors).orEmpty()

    /** The sensor being read. */
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

    private var started = false
    private var powerSave = false
    private var lastEventUptime = 0L
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

    private val keepAlive = object : Runnable {
        override fun run() {
            if (!started) return
            if (lastEventAgeMs() > STALE_MS) {
                Log.i(TAG, "no hinge events for ${lastEventAgeMs()}ms; re-registering")
                reRegister()
            }
            handler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

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
        handler.postDelayed(keepAlive, KEEPALIVE_INTERVAL_MS)
    }

    /**
     * Re-registers at a much lower rate while the system is in Battery Saver,
     * so the wallpaper still tracks a fold without the high-rate wakeups.
     */
    fun setPowerSave(enabled: Boolean) {
        if (powerSave == enabled) return
        powerSave = enabled
        if (!started) return
        reRegister()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(keepAlive)
        sensorManager?.unregisterListener(this)
    }

    private fun reRegister() {
        val s = sensor ?: return
        sensorManager?.unregisterListener(this)
        register()
        Log.i(TAG, "re-registered ${s.name}")
    }

    private fun register() {
        val s = sensor ?: run {
            Log.w(TAG, "no hinge sensor found")
            return
        }
        val target = if (powerSave) SLOW_PERIOD_US else FAST_PERIOD_US
        val periodUs = if (s.minDelay > target) s.minDelay else target
        val ok = sensorManager?.registerListener(this, s, periodUs) == true
        Log.i(
            TAG,
            "hinge sensor=${s.name} type=${s.stringType} wakeUp=${s.isWakeUpSensor} " +
                "minDelay=${s.minDelay}us period=${periodUs}us powerSave=$powerSave ok=$ok",
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        val raw = event.values.firstOrNull() ?: return
        if (!raw.isFinite()) return
        val now = SystemClock.uptimeMillis()
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
        /** 0 = fastest the sensor allows (clamped to minDelay), for instant tracking. */
        const val FAST_PERIOD_US = 0
        /** ~15 Hz under Battery Saver: plenty to follow a fold, far fewer wakeups. */
        const val SLOW_PERIOD_US = 66_000
        /** How often to check for a stalled sensor. */
        const val KEEPALIVE_INTERVAL_MS = 400L
        /** Silence longer than this triggers a re-registration to force a reading. */
        const val STALE_MS = 700L
    }
}
