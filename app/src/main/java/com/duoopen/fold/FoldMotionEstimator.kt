package com.duoopen.fold

import kotlin.math.PI
import kotlin.math.abs

/**
 * Estimates a smooth fold angle from the coarse hinge sensor plus the
 * gyroscope — the same idea as `keepYaoung/android-also-could-fold`.
 *
 * The public hinge sensor on several Samsung foldables reports only 0 / 90 /
 * 180, so the gyroscope's rotation about the fold axis (device Y) is integrated
 * between those anchors. This is a *relative* estimate, not a measured angle.
 *
 * It is deliberately bounded so it can never peg the CPU/GPU: it only runs
 * between a fold onset and a short quiet period, has a hard active cap, and
 * only emits when the value materially changes. A low "still" threshold would
 * let hand tremor keep it emitting at sensor rate forever, which janks the
 * device.
 */
class FoldMotionEstimator(private val onAngle: (Float) -> Unit) {

    private var anchor = Float.NaN
    private var estimated = Float.NaN
    private var lastEmitted = Float.NaN
    private var direction = 0          // +1 opening, -1 closing, 0 idle
    private var active = false
    private var lastGyroNs = 0L
    private var onsetSince = 0L
    private var lastMotionMs = 0L
    private var activeSinceMs = 0L

    fun onHinge(angle: Float, now: Long) {
        if (!angle.isFinite()) return
        val previous = anchor
        anchor = angle
        if (estimated.isNaN()) estimated = angle
        when {
            angle <= END_LO -> {
                estimated = 0f
                direction = 0
                active = false
                onsetSince = 0L
            }
            angle >= END_HI -> {
                estimated = 180f
                direction = 0
                active = false
                onsetSince = 0L
            }
            else -> {
                // Mid stop (typically 90°): infer direction, go active so the
                // gyro interpolates to the endpoint, and pull toward the stop.
                if (previous.isFinite() && previous != angle) {
                    direction = if (angle > previous) 1 else -1
                    if (!active) activeSinceMs = now
                    active = true
                    lastMotionMs = now
                    onsetSince = 0L
                }
                estimated = (estimated + (angle - estimated) * 0.5f).coerceIn(0f, 180f)
            }
        }
        emit(estimated, force = true)
    }

    fun onGyro(omegaY: Float, timestampNs: Long, now: Long) {
        val previous = lastGyroNs
        lastGyroNs = timestampNs
        if (previous == 0L) return
        val dt = (timestampNs - previous) / 1e9f
        if (dt <= 0f || dt > 0.25f) return
        val rate = abs(omegaY)
        val deltaDeg = rate * dt * 180f / PI.toFloat()

        if (!active) {
            // Start only from a rested endpoint, after sustained rotation.
            if ((anchor <= END_LO || anchor >= END_HI) && rate > ONSET_RATE) {
                if (onsetSince == 0L) onsetSince = now
                if (now - onsetSince >= ONSET_HOLD_MS) {
                    active = true
                    activeSinceMs = now
                    direction = if (anchor <= END_LO) 1 else -1
                    estimated = if (anchor <= END_LO) 0f else 180f
                    lastMotionMs = now
                    emit(estimated, force = true)
                }
            } else {
                onsetSince = 0L
            }
            return
        }

        if (rate >= STILL_RATE) {
            estimated = (estimated + direction * deltaDeg * RESPONSE).coerceIn(0f, 180f)
            lastMotionMs = now
            emit(estimated)
        }
        // Settle on a real quiet gap, and never stay active beyond the cap.
        if (now - lastMotionMs > SETTLE_MS || now - activeSinceMs > MAX_ACTIVE_MS) {
            active = false
            direction = 0
            onsetSince = 0L
        }
    }

    fun reset() {
        anchor = Float.NaN
        estimated = Float.NaN
        lastEmitted = Float.NaN
        direction = 0
        active = false
        lastGyroNs = 0L
        onsetSince = 0L
        lastMotionMs = 0L
        activeSinceMs = 0L
    }

    /** True while the gyro is actively driving the estimate. */
    val motionActive: Boolean get() = active

    private fun emit(angle: Float, force: Boolean = false) {
        if (!angle.isFinite()) return
        if (!force && lastEmitted.isFinite() && abs(angle - lastEmitted) < EMIT_DEADBAND) return
        lastEmitted = angle
        onAngle(angle)
    }

    private companion object {
        const val END_LO = 1f
        const val END_HI = 179f
        /** Sustained angular speed (rad/s) that starts a fold from rest. */
        const val ONSET_RATE = 0.15f
        const val ONSET_HOLD_MS = 200L
        /** Below this the phone counts as still (noise/tremor is ignored). */
        const val STILL_RATE = 0.18f
        const val SETTLE_MS = 400L
        /** Hard cap so a noisy sensor can never keep the effect running. */
        const val MAX_ACTIVE_MS = 5_000L
        const val RESPONSE = 1.0f
        /** Only report a new angle once it has moved this much. */
        const val EMIT_DEADBAND = 0.25f
    }
}
