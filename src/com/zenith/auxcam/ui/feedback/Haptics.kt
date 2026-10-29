package com.zenith.auxcam.ui.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Soft haptic vocabulary for the CMF Phone 2 Pro.
 *
 * The previous implementation drove `VibrationEffect` predefined effects, which on
 * this device fire the *main* actuator at full drive strength — every tap felt like
 * a notification buzz. Apple's camera feels precise because the feedback is short
 * and light; on Android the equivalent is the system's own haptic feedback channel
 * (`View.performHapticFeedback`), which is:
 *
 *  • rendered by the same tuned driver path as the keyboard and system UI,
 *  • scaled by the user's haptic intensity setting,
 *  • suppressed when touch feedback is disabled,
 *  • and inherently short (a tick, not a pulse).
 *
 * So this class is now **view-haptic first**. The vibrator is only used as a
 * last-resort fallback, and even then with a tiny amplitude and a ≤12 ms duration
 * so it can never feel like a full-strength vibration.
 */
class CameraHaptics(private val view: View) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (view.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /**
     * One detent of the zoom wheel — the lightest event in the vocabulary, so a long
     * pinch still reads as texture rather than a rattle.
     */
    fun tick() {
        if (softSegmentTick()) return
        if (soft(HapticFeedbackConstants.CLOCK_TICK)) return
        fallback()
    }

    /** Confirmed selection: lens chip, mode, toggle, aspect ratio. */
    fun select() {
        if (soft(HapticFeedbackConstants.CONTEXT_CLICK)) return
        if (soft(HapticFeedbackConstants.VIRTUAL_KEY)) return
        fallback()
    }

    /** Generic button acknowledgement. */
    fun tap() {
        if (soft(HapticFeedbackConstants.KEYBOARD_TAP)) return
        if (soft(HapticFeedbackConstants.VIRTUAL_KEY)) return
        fallback()
    }

    /** Finger down on the shutter — the heaviest event, still a single tick. */
    fun shutterDown() {
        if (soft(HapticFeedbackConstants.LONG_PRESS)) return
        if (soft(HapticFeedbackConstants.VIRTUAL_KEY)) return
        fallback()
    }

    /** Frame captured. Two soft ticks rather than a heavy click pattern. */
    fun capture() {
        if (soft(HapticFeedbackConstants.CONFIRM)) return
        if (soft(HapticFeedbackConstants.VIRTUAL_KEY)) return
        fallback()
    }

    /** AE/AF lock engaged. */
    fun focusLock() {
        if (soft(HapticFeedbackConstants.LONG_PRESS)) return
        if (soft(HapticFeedbackConstants.CONTEXT_CLICK)) return
        fallback()
    }

    /** AE/AF lock released. */
    fun focusUnlock() {
        if (soft(HapticFeedbackConstants.CONTEXT_CLICK)) return
        if (soft(HapticFeedbackConstants.VIRTUAL_KEY)) return
        fallback()
    }

    /** AE/AF acquired at a tapped point. */
    fun focus() {
        if (soft(HapticFeedbackConstants.CONTEXT_CLICK)) return
        if (soft(HapticFeedbackConstants.VIRTUAL_KEY)) return
        fallback()
    }

    /** Rejection: nothing to do, or an action that cannot run yet. */
    fun reject() {
        if (soft(HapticFeedbackConstants.REJECT)) return
        if (soft(HapticFeedbackConstants.LONG_PRESS)) return
        fallback()
    }

    /** End of a gesture (pinch, hold-to-lock release). */
    fun gestureEnd() {
        if (soft(HapticFeedbackConstants.GESTURE_END)) return
        if (soft(HapticFeedbackConstants.CLOCK_TICK)) return
        fallback()
    }

    /**
     * Fires a system haptic constant and reports whether anything happened.
     * `performHapticFeedback` returns false when touch feedback is off, which lets
     * the caller try the next, lighter constant.
     */
    private fun soft(constant: Int): Boolean =
        runCatching { view.performHapticFeedback(constant) }.getOrDefault(false)

    /**
     * Last resort only: a single 10 ms, low-amplitude tick. Amplitude is kept at ~30
     * of 255 so a device without the view-haptic path still gets a *soft* nudge.
     */
    private fun fallback() {
        val v = vibrator ?: return
        if (!runCatching { v.hasVibrator() }.getOrDefault(false)) return
        runCatching {
            val effect = VibrationEffect.createOneShot(10L, 30)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
        }
    }

    /**
     * `SEGMENT_TICK` (API 34) is the softest tick the platform exposes — the one
     * sliders use. Guarded here rather than referenced directly so older releases
     * never touch the field.
     */
    private fun softSegmentTick(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) soft(HapticFeedbackConstants.SEGMENT_TICK) else false
}

/** Remembers one haptics handle per composition (it holds a [View] reference). */
@Composable
fun rememberCameraHaptics(): CameraHaptics {
    val view = LocalView.current
    return remember(view) { CameraHaptics(view) }
}
