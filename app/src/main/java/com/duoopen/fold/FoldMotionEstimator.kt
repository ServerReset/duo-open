package com.duoopen.fold

import kotlin.math.PI
import kotlin.math.abs

/**
 * Estimates a smooth fold angle from the coarse hinge sensor plus the
 * gyroscope — the same idea as `keepYaoung/android-also-could-fold`.
 *
 * The public hinge sensor on several Samsung foldables reports only 0 / 90 /
 * 180, so the gyroscope's rotation about the fold axis (device Y) is integrated
 * between those anchors to drive the visual smoothly. This is a *relative
 * estimate*, not a measured angle: moving the whole device can contaminate it,
 * so it only starts from a rested endpoint, is re-anchored by every hinge
 * reading, and settles shortly after motion stops.
 */
class FoldMotionEstimator(private val onAngle: (Float) -> Unit) {

    private var anchor = Float.NaN
    private var estimated = Float.NaN
    private var direction = 0          // +1 opening, -1 closing, 0 idle
    private var active = false
    private var lastGyroNs = 0L
    private var onsetSince = 0L
    private var lastMotionMs = 0L

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
                // Mid stop (typically 90°): pull the estimate halfway toward it
                // and infer the direction from where we came from.
                if (previous.isFinite() && previous != angle && !active) {
                    direction = if (angle > previous) 1 else -1
                }
                estimated = (estimated + (angle - estimated) * 0.5f).coerceIn(0f, 180f)
            }
        }
        emit(estimated)
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
                    direction = if (anchor <= END_LO) 1 else -1
                    estimated = if (anchor <= END_LO) 0f else 180f
                    lastMotionMs = now
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
        if (now - lastMotionMs > SETTLE_MS) {
            active = false
            direction = 0
            onsetSince = 0L
            emit(estimated)
        }
    }

    fun reset() {
        anchor = Float.NaN
        estimated = Float.NaN
        direction = 0
        active = false
        lastGyroNs = 0L
        onsetSince = 0L
        lastMotionMs = 0L
    }

    private fun emit(angle: Float) {
        if (angle.isFinite()) onAngle(angle)
    }

    private companion object {
        const val END_LO = 1f
        const val END_HI = 179f
        /** Sustained angular speed (rad/s) that starts a fold from rest. */
        const val ONSET_RATE = 0.6f
        const val ONSET_HOLD_MS = 150L
        const val STILL_RATE = 0.1f
        const val SETTLE_MS = 500L
        const val RESPONSE = 1.0f
    }
}
