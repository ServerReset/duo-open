package com.duoopen.fold

import kotlin.math.PI
import kotlin.math.abs

/**
 * A "1€" adaptive low-pass filter (Casiez, Roussel & Vogel 2012) tuned for
 * hinge angles.
 *
 * Why it's here: several foldables — the Galaxy Z Fold line in particular —
 * don't hand the app a perfectly smooth angle. Their hinge HAL reports in
 * coarse steps (often whole degrees, sometimes larger detents) and adds a
 * little sensor noise on top. Applied directly, that reads on screen as a
 * staircase that jitters around each step.
 *
 * This filter kills the noise while the hinge is nearly still, then drops its
 * time constant as the hinge speeds up so a real fold still feels attached to
 * the hardware. The net effect is a continuous, "live" angle curve rather than
 * incremented steps.
 *
 * @param minCutoff Cutoff (Hz) at rest. Lower = smoother but laggier.
 * @param beta How aggressively the cutoff opens up with speed. Higher =
 *   snappier, at the cost of letting more jitter through during motion.
 * @param derivativeCutoff Cutoff (Hz) for the internal speed estimate.
 */
class AngleFilter(
    private val minCutoff: Float = 2.0f,
    private val beta: Float = 0.03f,
    private val derivativeCutoff: Float = 1.0f,
) {

    private var primed = false
    private var lastRaw = 0f
    private var lastTimeNanos = 0L
    private var smoothed = 0f
    private var smoothedDerivative = 0f

    /** Drops all state so the next sample is treated as the first. */
    fun reset() {
        primed = false
        lastTimeNanos = 0L
    }

    /**
     * Feeds one raw sample and returns the smoothed angle. [timestampNanos]
     * should be the sensor event time so the filter follows real elapsed time.
     */
    fun filter(value: Float, timestampNanos: Long): Float {
        if (!primed || lastTimeNanos == 0L || timestampNanos <= lastTimeNanos) {
            primed = true
            lastRaw = value
            lastTimeNanos = timestampNanos
            smoothed = value
            smoothedDerivative = 0f
            return value
        }

        val dt = (timestampNanos - lastTimeNanos) / 1e9f
        // Guard against clock resets and long gaps (screen off, batched events).
        if (dt <= 0f || dt > 1f) {
            lastRaw = value
            lastTimeNanos = timestampNanos
            return smoothed
        }

        val derivative = (value - lastRaw) / dt
        smoothedDerivative += (derivative - smoothedDerivative) * alpha(derivativeCutoff, dt)

        val cutoff = minCutoff + beta * abs(smoothedDerivative)
        smoothed += (value - smoothed) * alpha(cutoff, dt)

        lastRaw = value
        lastTimeNanos = timestampNanos
        return smoothed
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return dt / (dt + tau)
    }
}
