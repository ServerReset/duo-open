package com.duoopen.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.isInnerPanel
import com.duoopen.power.PowerState
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System-wide fold effect. Accessibility services may screenshot the display
 * and draw above every other window, which is what lets the fold cover the
 * launcher, lock screen and apps — not just the wallpaper.
 *
 * Opening: the inner panel comes up → one screenshot (retried if the panel
 * was still black) → a full-screen, touch-transparent overlay draws it
 * through the fold shader, tracking the hinge → removed once flat.
 * Closing: the hinge leaves flat → screenshot → same overlay, removed when
 * the panel switches to the cover screen.
 *
 * The snapshot is frozen for the fraction of a second of a fold, which is
 * invisible in practice; if the hinge stops partway (tent mode) the overlay
 * fades out instead so live content isn't hidden.
 */
class FoldOverlayService : AccessibilityService() {

    private enum class Phase { IDLE, CAPTURING, SHOWING }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var hinge: HingeAngleSource
    private lateinit var displayManager: DisplayManager
    private var windowManager: WindowManager? = null

    private var phase = Phase.IDLE
    private var overlay: FoldOverlayView? = null
    private var follower: TiltFollower? = null

    /** Which panel is live; the effect restarts whenever this flips mid-fold. */
    private var innerPanel = false
    /** Set at a rest pose (closed on the cover, flat on the inner) so leaving it plays once. */
    private var restArmed = true
    private var panelSwitched = false
    private var lastHingeMoveMs = 0L
    /** Throttle for evaluate() so sensor-rate events don't hammer display state. */
    private var lastEvaluateMs = 0L
    /** When the current overlay started showing, for the max-show safety. */
    private var shownSinceMs = 0L
    private var demoRunning = false
    /** Bumped per capture so a late or hung screenshot can't act on a newer phase. */
    private var captureGen = 0
    /** Overlay is resolving on a timer, ignoring the hinge (see [show]). */
    private var timedResolve = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) evaluate()
        }
    }

    private val settleCheck = object : Runnable {
        override fun run() {
            val o = overlay ?: return
            if (o.tilt < DuoShader.FLAT_EPSILON) return
            val now = SystemClock.uptimeMillis()
            if (!demoRunning &&
                (now - lastHingeMoveMs >= SETTLE_TIMEOUT_MS || now - shownSinceMs >= MAX_SHOW_MS)
            ) {
                dismiss(fadeMs = FADE_OUT_STALLED_MS)
            } else {
                handler.postDelayed(this, 150)
            }
        }
    }

    /** `adb shell am broadcast -a com.duoopen.DEMO` plays the effect over whatever is on screen. */
    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = playDemo()
    }
    private var receiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!receiverRegistered) {
            registerReceiver(demoReceiver, IntentFilter(ACTION_DEMO), RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
        hinge = HingeAngleSource(this) { onHinge(it) }
        hinge.start()
        observePower()
        innerPanel = defaultDisplay().isInnerPanel()
        Log.i(TAG, "connected; hinge=${hinge.sensor?.name} inner=$innerPanel")
    }

    override fun onDestroy() {
        instance = null
        if (receiverRegistered) unregisterReceiver(demoReceiver)
        hinge.stop()
        displayManager.unregisterDisplayListener(displayListener)
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun defaultDisplay(): Display? = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    /**
     * Battery behavior: stop the sensor while the screen is off, and when
     * Battery Saver kicks in, drop to a slow sample rate and tear down any
     * overlay (the capture + full-screen render is the expensive part).
     */
    private fun observePower() {
        scope.launch {
            PowerState.screenOn.collect { on -> if (on) hinge.start() else hinge.stop() }
        }
        scope.launch { PowerState.batterySaver.collect { applyPowerSave() } }
        scope.launch { DuoSettings.config.collect { applyPowerSave() } }
    }

    private fun applyPowerSave() {
        val reduce = DuoSettings.config.value.powerSaveReducesEffect
        val saving = PowerState.batterySaver.value
        hinge.setPowerSave(reduce && saving)
        if (reduce && saving && phase != Phase.IDLE) {
            Log.i(TAG, "battery saver on; standing the effect down")
            removeOverlay()
        }
    }

    private fun effectSuppressed(): Boolean =
        DuoSettings.config.value.powerSaveReducesEffect && PowerState.batterySaver.value

    private fun currentTilt(): Float =
        DuoShader.tiltFor(hinge.lastAngle, DuoSettings.config.value, innerPanel)

    private fun onHinge(angle: Float) {
        if (!demoRunning && effectSuppressed()) return
        // Never show the fold while the phone is essentially flat (>=175°), and
        // never while the raw sensor sits at the open endpoint with no gyro
        // motion — that is a stuck/false reading, not a fold.
        if (angle > TRIGGER_HINGE || (hinge.rawAngle >= 179f && !hinge.gyroConfirmed)) {
            restArmed = true
            panelSwitched = false
            if (phase == Phase.SHOWING) dismiss(fadeMs = FADE_OUT_FLAT_MS)
            return
        }
        val now = SystemClock.uptimeMillis()
        lastHingeMoveMs = now
        // evaluate() hits the display state; throttle it. The follower below is
        // what tracks the angle at sensor rate.
        if (now - lastEvaluateMs >= EVALUATE_MIN_INTERVAL_MS) {
            lastEvaluateMs = now
            evaluate()
        }
        val tilt = DuoShader.tiltFor(angle, DuoSettings.config.value, innerPanel)
        if (timedResolve) return
        if (tilt < DuoShader.FLAT_EPSILON && phase == Phase.SHOWING && !demoRunning) {
            // At rest: drop the overlay now rather than easing the last degrees.
            dismiss(fadeMs = FADE_OUT_FLAT_MS)
        } else {
            if (!demoRunning) follower?.tauS = TiltFollower.tauForGap(hinge.eventGapMs)
            follower?.setTarget(tilt)
        }
    }

    /**
     * Drives the effect from two signals: the live panel and the hinge angle.
     * Whichever panel is up, the picture is flat at its rest pose (cover
     * closed, inner open) and fully frosted at the panel swap, so an open or a
     * close is one continuous frost-up on the first panel and frost-down on
     * the second. A capture starts on leaving rest and again on each swap.
     */
    private fun evaluate() {
        if (demoRunning) return
        if (effectSuppressed()) return
        val inner = defaultDisplay().isInnerPanel()
        if (inner != innerPanel) {
            innerPanel = inner
            panelSwitched = true
            if (phase != Phase.IDLE) removeOverlay() // old panel's snapshot is meaningless now
        }
        val angle = hinge.lastAngle
        if (angle.isNaN()) return
        // Only act while the hinge is actually moving; a static mid-angle
        // reading must not keep the effect up.
        if (hinge.millisSinceChange() > MOVE_WINDOW_MS) {
            restArmed = true
            panelSwitched = false
            return
        }
        // Same flat/stuck guard as onHinge: no capture while >=175° or while the
        // raw sensor sits at the open endpoint with no gyro motion.
        if (angle > TRIGGER_HINGE || (hinge.rawAngle >= 179f && !hinge.gyroConfirmed)) {
            restArmed = true
            panelSwitched = false
            return
        }
        val tilt = DuoShader.tiltFor(angle, DuoSettings.config.value, inner)
        if (tilt < DuoShader.FLAT_EPSILON) {
            restArmed = true
            panelSwitched = false
            return
        }
        if (phase != Phase.IDLE) return
        when {
            panelSwitched -> {
                panelSwitched = false
                restArmed = false
                Log.i(TAG, "panel swapped (inner=$inner) at hinge=$angle")
                // The fresh panel may still be lighting up: retry if black.
                startCapture(afterSwap = true)
            }
            restArmed && tilt >= REST_LEAVE_TILT -> {
                restArmed = false
                Log.i(TAG, "leaving rest (inner=$inner) at hinge=$angle")
                startCapture(afterSwap = false)
            }
        }
    }

    private fun startCapture(afterSwap: Boolean, startTilt: Float? = null) {
        phase = Phase.CAPTURING
        capture(gen = ++captureGen, attempt = 1, afterSwap = afterSwap, startTilt = startTilt)
    }

    private fun capture(gen: Int, attempt: Int, afterSwap: Boolean, startTilt: Float?) {
        val t0 = SystemClock.uptimeMillis()
        fun stale() = gen != captureGen || phase != Phase.CAPTURING
        // The framework refuses captures closer than ~333 ms apart, measured
        // from the previous request — so a slow capture costs no extra wait.
        fun retry() {
            val wait = (t0 + SCREENSHOT_MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed({ if (!stale()) capture(gen, attempt + 1, afterSwap, startTilt) }, wait)
        }
        // A screenshot requested as a panel switches off may never call back.
        handler.postDelayed({
            if (!stale()) {
                Log.w(TAG, "capture $attempt timed out; giving up")
                phase = Phase.IDLE
                demoRunning = false
            }
        }, CAPTURE_TIMEOUT_MS)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                buffer.close()
                if (stale()) {
                    Log.i(TAG, "stale capture after ${SystemClock.uptimeMillis() - t0}ms; dropped")
                    bitmap?.recycle()
                    return
                }
                if (bitmap == null) {
                    Log.w(TAG, "screenshot buffer could not be wrapped")
                    phase = Phase.IDLE
                    return
                }
                if (!afterSwap || attempt >= MAX_CAPTURE_ATTEMPTS) {
                    onCaptured(bitmap, afterSwap, startTilt, t0)
                    return
                }
                scope.launch {
                    val black = withContext(Dispatchers.Default) { isMostlyBlack(bitmap) }
                    if (stale()) {
                        bitmap.recycle()
                        return@launch
                    }
                    if (black && !demoRunning) {
                        bitmap.recycle()
                        Log.i(TAG, "capture $attempt is black after ${SystemClock.uptimeMillis() - t0}ms; retrying")
                        retry()
                    } else {
                        onCaptured(bitmap, afterSwap, startTilt, t0)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (stale()) return
                // Secure content (banking, DRM video) and rate limits land here.
                Log.w(TAG, "screenshot failed: $errorCode")
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < MAX_CAPTURE_ATTEMPTS) {
                    retry()
                } else {
                    phase = Phase.IDLE
                    demoRunning = false
                }
            }
        })
    }

    private fun onCaptured(bitmap: Bitmap, afterSwap: Boolean, startTilt: Float?, t0: Long) {
        if (phase != Phase.CAPTURING) {
            bitmap.recycle()
            return
        }
        val angle = hinge.lastAngle
        val tilt = startTilt ?: currentTilt()
        val nearlyDone = afterSwap && startTilt == null &&
            if (innerPanel) angle > SKIP_INNER_ABOVE_HINGE else angle < SKIP_COVER_BELOW_HINGE
        if (tilt < DuoShader.FLAT_EPSILON || nearlyDone) {
            // Too late to be worth a pop-in: the fold is (almost) over.
            Log.i(TAG, "hinge=$angle by capture time (${SystemClock.uptimeMillis() - t0}ms); skipping")
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        // Closing onto the cover: the hinge HAL goes quiet around 30°, so the
        // overlay would never hear "closed". Resolve on a timer instead — by
        // the time this capture lands the phone is shut anyway, so it reads
        // as the cover settling into focus.
        val timed = afterSwap && !innerPanel && startTilt == null
        Log.i(TAG, "showing ${bitmap.width}x${bitmap.height} at tilt=$tilt (capture ${SystemClock.uptimeMillis() - t0}ms)${if (timed) " timed" else ""}")
        show(bitmap, tilt, fadeIn = afterSwap, timed = timed)
    }

    /** Samples a coarse grid; true when nothing on screen is brighter than near-black. */
    private fun isMostlyBlack(hw: Bitmap): Boolean {
        val sw = runCatching { hw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return false
        try {
            val n = 24
            val pixels = IntArray(n * n)
            for (iy in 0 until n) {
                val y = ((iy + 0.5f) * sw.height / n).toInt()
                for (ix in 0 until n) {
                    val x = ((ix + 0.5f) * sw.width / n).toInt()
                    pixels[iy * n + ix] = sw.getPixel(x, y)
                }
            }
            var maxSum = 0
            for (c in pixels) {
                val sum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                if (sum > maxSum) maxSum = sum
            }
            return maxSum < BLACK_THRESHOLD
        } finally {
            sw.recycle()
        }
    }

    private fun show(bitmap: Bitmap, startTilt: Float, fadeIn: Boolean = false, timed: Boolean = false) {
        val display = defaultDisplay() ?: run { bitmap.recycle(); phase = Phase.IDLE; return }
        val wm = windowManager ?: createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
            .also { windowManager = it }

        val inner = innerPanel
        val view = FoldOverlayView(this, bitmap, { w, h, c -> DuoShader.foldFor(inner, w, h, c) }).apply {
            config = DuoSettings.config.value
            tilt = startTilt
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "DuoOpenFold"
        }
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            bitmap.recycle()
            phase = Phase.IDLE
            return
        }
        overlay = view
        phase = Phase.SHOWING
        shownSinceMs = SystemClock.uptimeMillis()
        OverlayState.setRunning(true)
        if (fadeIn) {
            // Content was already live on this panel; ease the frost in.
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
        follower = TiltFollower { t ->
            view.tilt = t
            if (t < DuoShader.FLAT_EPSILON && !demoRunning) dismiss(fadeMs = FADE_OUT_FLAT_MS)
        }.also {
            it.snap(startTilt)
            it.tauS = TiltFollower.tauForGap(hinge.eventGapMs)
        }
        lastHingeMoveMs = SystemClock.uptimeMillis()
        timedResolve = timed
        if (timed) {
            follower?.tauS = TIMED_RESOLVE_TAU_S
            follower?.setTarget(0f)
        } else {
            handler.postDelayed(settleCheck, SETTLE_TIMEOUT_MS)
        }
    }

    private fun dismiss(fadeMs: Long) {
        val view = overlay ?: return
        Log.i(TAG, "dismiss (fade ${fadeMs}ms) at tilt=${view.tilt}")
        handler.removeCallbacks(settleCheck)
        follower?.cancel()
        follower = null
        overlay = null
        timedResolve = false
        phase = Phase.IDLE
        view.animate()
            .alpha(0f)
            .setDuration(fadeMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { detach(view) }
            .start()
    }

    private fun removeOverlay() {
        phase = Phase.IDLE
        timedResolve = false
        val view = overlay ?: return
        handler.removeCallbacks(settleCheck)
        follower?.cancel()
        follower = null
        overlay = null
        detach(view)
    }

    private fun detach(view: FoldOverlayView) {
        runCatching { windowManager?.removeViewImmediate(view) }
        OverlayState.setRunning(false)
    }

    /**
     * Manual check without folding: snapshot the screen and play what this
     * panel shows during a fold. Inner panel: an unfold from full frost to
     * flat. Cover panel: frost sweeping in (opening) then back out (closing).
     */
    fun playDemo(durationMs: Long = 1400) {
        if (phase != Phase.IDLE || demoRunning) return
        demoRunning = true
        val inner = innerPanel
        val peak = DuoShader.MAX_TILT * DuoSettings.config.value.intensity.coerceAtMost(1f)
        // Frost at the very start so the overlay is visibly there; on the
        // cover it starts flat and sweeps in first.
        startCapture(afterSwap = false, startTilt = if (inner) peak else 0.06f)
        handler.postDelayed({
            val f = follower
            if (f == null) {
                demoRunning = false
                return@postDelayed
            }
            // Slow ease so the demo reads as a fold rather than a snap.
            f.tauS = durationMs / 4000f
            if (inner) {
                f.setTarget(0f)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs)
            } else {
                f.setTarget(peak)
                handler.postDelayed({ f.setTarget(0f) }, durationMs)
                handler.postDelayed({
                    demoRunning = false
                    dismiss(fadeMs = FADE_OUT_FLAT_MS)
                }, durationMs * 2)
            }
        }, 450)
    }

    companion object {
        private const val TAG = "DuoOverlay"
        const val ACTION_DEMO = "com.duoopen.DEMO"
        /** The framework rejects screenshots closer together than ~333 ms. */
        private const val SCREENSHOT_MIN_INTERVAL_MS = 340L
        private const val MAX_CAPTURE_ATTEMPTS = 3
        private const val CAPTURE_TIMEOUT_MS = 800L
        private const val BLACK_THRESHOLD = 30
        /** Ease time constant for the timed resolve (≈ 250 ms to settle). */
        private const val TIMED_RESOLVE_TAU_S = 0.07f
        /** Tilt hysteresis for leaving a rest pose, so hinge jitter doesn't fire. */
        private const val REST_LEAVE_TILT = 3f
        /** After a swap, don't bother if the fold is nearly finished by capture time. */
        private const val SKIP_INNER_ABOVE_HINGE = 135f
        private const val SKIP_COVER_BELOW_HINGE = 10f
        private const val SETTLE_TIMEOUT_MS = 700L
        private const val EVALUATE_MIN_INTERVAL_MS = 50L
        /** Don't start/show the effect while the phone is essentially flat. */
        private const val TRIGGER_HINGE = 175f
        /** The hinge must have changed within this window to count as moving. */
        private const val MOVE_WINDOW_MS = 500L
        /** Hard cap on a single overlay showing, so it can never stay stuck. */
        private const val MAX_SHOW_MS = 3_000L
        private const val FADE_IN_MS = 140L
        private const val FADE_OUT_FLAT_MS = 120L
        private const val FADE_OUT_STALLED_MS = 300L

        /** The connected service, for in-process control from the app. */
        @Volatile
        var instance: FoldOverlayService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val self = ComponentName(context, FoldOverlayService::class.java)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == self }
        }
    }
}
