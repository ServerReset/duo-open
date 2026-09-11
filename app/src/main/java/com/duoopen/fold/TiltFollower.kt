package com.duoopen.fold

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.exp

/**
 * Eases a displayed tilt toward the latest hinge-derived target, one vsync at
 * a time. Hides the sensor's 1° steps (~50 Hz on the OnePlus Open) without
 * adding noticeable lag; idles when converged. Main thread only.
 */
class TiltFollower(private val onFrame: (tilt: Float) -> Unit) : Choreographer.FrameCallback {

    var target = 0f
        private set
    var current = 0f
        private set

    /** Time constant of the ease; larger = slower. */
    var tauS = DEFAULT_TAU_S

    private var scheduled = false
    private var lastFrameNanos = 0L

    fun setTarget(tilt: Float) {
        target = tilt
        if (current != target) schedule()
    }

    /** Jumps straight to [tilt] with no animation or callback. */
    fun snap(tilt: Float) {
        target = tilt
        current = tilt
        cancel()
    }

    fun cancel() {
        if (!scheduled) return
        scheduled = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        val dt = if (lastFrameNanos == 0L) 1f / 60f
        else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = frameTimeNanos

        current += (target - current) * (1f - exp(-dt / tauS))
        if (abs(target - current) < 0.02f) current = target

        onFrame(current)
        if (current != target) schedule() else lastFrameNanos = 0L
    }

    companion object {
        /**
         * Small time constant: just enough to smooth the sensor's discrete
         * steps without a perceptible lag behind the hinge.
         */
        const val DEFAULT_TAU_S = 0.02f
    }
}
