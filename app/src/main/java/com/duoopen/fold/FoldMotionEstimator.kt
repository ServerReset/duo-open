package com.duoopen.fold

import kotlin.math.PI
import kotlin.math.abs

/**
 * Smooths the coarse hinge angle with the gyroscope, the way
 * `keepYaoung/android-also-could-fold` does — but with the gyro strictly
 * subordinate to the hinge sensor.
 *
 * The Z Fold's public hinge sensor only reports 0 / 90 / 180. The gyroscope's
 * rotation about the fold axis (device Y) is integrated *between* those hinge
 * readings so the effect animates continuously.
 *
 * Two rules keep it honest and stable:
 *  - The gyro can only *sustain* a fold. A fold starts from a real hinge change,
 *    or (from a rested endpoint) a tentative gyro onset that is cancelled within
 *    ~1.5 s unless a hinge change confirms it. So merely tilting the phone can
 *    never start or hold the effect.
 *  - Everything is bounded: emits are deadbanded, the gyro is quiet-gated, and
 *    there is a hard active cap, so it can't peg the device.
 */
class FoldMotionEstimator(private val onAngle: (Float) -> Unit) {

    private var anchor = Float.NaN
    private var estimated = Float.NaN
    private var lastEmitted = Float.NaN
    private var direction = 0          // +1 opening, -1 closing, 0 idle
    private var active = false         // tracking a fold
    private var confirmed = false      // a hinge change has confirmed it
    private var lastGyroNs = 0L
    private var onsetSince = 0L
    private var lastMotionMs = 0L
    private var activeSinceMs = 0L
    private var tentativeSinceMs = 0L

    /** True while the gyro is driving the estimate. */
    val motionActive: Boolean get() = active

    /** True once a real hinge change has confirmed the fold (not gyro-only). */
    val motionConfirmed: Boolean get() = active && confirmed

    fun onHinge(angle: Float, now: Long) {
        if (!angle.isFinite()) return
        val previous = anchor
        val changed = !previous.isFinite() || abs(angle - previous) >= HINGE_CHANGE_DEG
        anchor = angle
        if (estimated.isNaN()) estimated = angle
        when {
            angle <= END_LO -> {
                estimated = 0f
                finish()
            }
            angle >= END_HI -> {
                estimated = 180f
                finish()
            }
            else -> {
                if (changed) {
                    // A real hinge change is the only way to start/confirm a fold.
                    direction = when {
                        !previous.isFinite() -> if (estimated < angle) 1 else -1
                        angle > previous -> 1
                        else -> -1
                    }
                    if (!active) activeSinceMs = now
                    active = true
                    confirmed = true
                    lastMotionMs = now
                    estimated = angle
                }
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
            // Tentative start only from a rested endpoint, after sustained
            // rotation. Cancelled below unless a hinge change confirms it.
            if ((anchor <= END_LO || anchor >= END_HI) && rate > ONSET_RATE) {
                if (onsetSince == 0L) onsetSince = now
                if (now - onsetSince >= ONSET_HOLD_MS) {
                    active = true
                    confirmed = false
                    tentativeSinceMs = now
                    activeSinceMs = now
                    direction = if (anchor <= END_LO) 1 else -1
                    estimated = if (anchor <= END_LO) 0f else 180f
                    lastMotionMs = now
                    emit(estimated, force = true)
                }
            } else {
                onsetSince = 0L
            }
            if (!active) return
        }

        if (rate >= STILL_RATE) {
            estimated = (estimated + direction * deltaDeg * RESPONSE).coerceIn(0f, 180f)
            lastMotionMs = now
            emit(estimated)
        }

        val now2 = now
        when {
            // Gyro-only start that the hinge never confirmed: it was a tilt.
            !confirmed && now2 - tentativeSinceMs > TENTATIVE_MS -> {
                estimated = anchor.coerceIn(0f, 180f)
                emit(estimated, force = true)
                finish()
            }
            now2 - lastMotionMs > SETTLE_MS -> finish()
            now2 - activeSinceMs > MAX_ACTIVE_MS -> finish()
        }
    }

    fun reset() {
        anchor = Float.NaN
        estimated = Float.NaN
        lastEmitted = Float.NaN
        finish()
    }

    private fun finish() {
        direction = 0
        active = false
        confirmed = false
        onsetSince = 0L
        lastMotionMs = 0L
        activeSinceMs = 0L
        tentativeSinceMs = 0L
    }

    private fun emit(angle: Float, force: Boolean = false) {
        if (!angle.isFinite()) return
        if (!force && lastEmitted.isFinite() && abs(angle - lastEmitted) < EMIT_DEADBAND) return
        lastEmitted = angle
        onAngle(angle)
    }

    private companion object {
        const val END_LO = 1f
        const val END_HI = 179f
        /** A hinge reading only counts as a change (a fold event) above this. */
        const val HINGE_CHANGE_DEG = 1f
        /** Sustained angular speed (rad/s) for a tentative gyro start. */
        const val ONSET_RATE = 0.2f
        const val ONSET_HOLD_MS = 150L
        /** Below this the gyro is treated as still. */
        const val STILL_RATE = 0.05f
        const val SETTLE_MS = 500L
        /** A tentative (gyro-only) start must be hinge-confirmed within this. */
        const val TENTATIVE_MS = 1_500L
        const val MAX_ACTIVE_MS = 6_000L
        const val RESPONSE = 1.0f
        const val EMIT_DEADBAND = 0.25f
    }
}
