package com.zenith.auxcam.ui.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptic vocabulary for the capture surface.
 *
 * Apple's camera feels physical because each interaction has a *distinct*,
 * very short tick — the shutter is the only heavy one. `View.performHapticFeedback`
 * alone cannot express that range, so on Android 10+ this drives the vibrator's
 * predefined effects (`EFFECT_TICK` / `EFFECT_CLICK` / `EFFECT_HEAVY_CLICK`) and
 * falls back to the older view constants elsewhere.
 *
 * Everything respects the system haptic setting: predefined effects are
 * silently dropped when the user has turned touch feedback off.
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

    private val hasPredefined =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            vibrator != null &&
            runCatching { vibrator.hasVibrator() }.getOrDefault(false)

    /**
     * One detent of the zoom wheel: the lightest event in the vocabulary, so it
     * still reads as a texture when the user spins through 20 ticks.
     */
    fun tick() = fire(VibrationEffect.EFFECT_TICK, HapticFeedbackConstants.CLOCK_TICK)

    /** Confirmed selection: lens chip, mode, toggle. */
    fun select() = fire(VibrationEffect.EFFECT_CLICK, HapticFeedbackConstants.CONTEXT_CLICK)

    /** Generic button acknowledgement. */
    fun tap() = fire(VibrationEffect.EFFECT_CLICK, HapticFeedbackConstants.KEYBOARD_TAP)

    /** Finger down on the shutter — the "armed" thunk. */
    fun shutterDown() = fire(VibrationEffect.EFFECT_HEAVY_CLICK, HapticFeedbackConstants.LONG_PRESS)

    /** Frame actually captured: a double click, the only "rich" pattern here. */
    fun capture() = fire(
        VibrationEffect.EFFECT_DOUBLE_CLICK,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    /** AE/AF lock at a tapped point. */
    fun focus() = fire(VibrationEffect.EFFECT_CLICK, HapticFeedbackConstants.CONTEXT_CLICK)

    /** Rejection: nothing to do, or an action that cannot run yet. */
    fun reject() = viewHaptic(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    /** End of a gesture (dial released, pinch finished). */
    fun gestureEnd() = viewHaptic(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_END
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        },
    )

    /** Plays a predefined effect, or the given view constant when unsupported. */
    private fun fire(effectId: Int, viewConstant: Int) {
        if (effect(effectId)) return
        viewHaptic(viewConstant)
    }

    private fun effect(id: Int): Boolean {
        if (!hasPredefined) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val v = vibrator ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createPredefined(id))
            true
        }.getOrDefault(false)
    }

    private fun viewHaptic(constant: Int): Boolean =
        runCatching { view.performHapticFeedback(constant) }.getOrDefault(false)
}

/** Remembers one haptics handle per composition (it holds a [View] reference). */
@Composable
fun rememberCameraHaptics(): CameraHaptics {
    val view = LocalView.current
    return remember(view) { CameraHaptics(view) }
}
