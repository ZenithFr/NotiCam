package com.zenith.auxcam.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.ui.feedback.rememberCameraHaptics
import com.zenith.auxcam.ui.effects.rememberMatteGrain
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraSpacing

/**
 * Apple Camera shutter.
 *
 * Composition at a 72 dp visual size, inside a 96 dp paint/touch box:
 *   • 4 dp white ring
 *   • 3.5 dp transparent gap
 *   • solid white disc (disabled red disc in Video)
 *
 * Sizing note: the composable is deliberately **larger** than the disc so the
 * capture pulse ring (which expands towards 44 dp) is drawn inside its own
 * bounds. The previous View implementation drew the pulse past the view edge and
 * clipped it; here the extra 24 dp plus `clip = false` on the graphics layer make
 * clipping impossible while the press animation scales.
 */
@Composable
fun ShutterButton(
    videoMode: Boolean,
    enabled: Boolean,
    pulse: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    recording: Boolean = false,
) {
    val grain = rememberMatteGrain()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberCameraHaptics()

    // Press: fast, slightly damped compression. Release: springy, no flicker.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.86f else 1f,
        animationSpec = if (pressed && enabled) CameraMotion.press() else CameraMotion.release(),
        label = "shutter-press",
    )

    val busyAmount by animateFloatAsState(
        targetValue = if (busy) 1f else 0f,
        animationSpec = CameraMotion.fluid(), label = "capture-busy",
    )
    val enabledAmount by animateFloatAsState(
        targetValue = if (enabled || busy) 1f else 0.55f,
        animationSpec = CameraMotion.fade(200), label = "shutter-availability",
    )
    val recordingShape by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = CameraMotion.fluid(), label = "record-stop-shape",
    )
    val discColor by animateColorAsState(
        targetValue = if (videoMode) CameraColors.Red else Color.White,
        animationSpec = CameraMotion.select(), label = "shutter-mode",
    )
    // Arm the shutter the moment the finger lands: heavy click, like a real button.
    LaunchedEffect(pressed) {
        if (pressed && enabled) haptics.shutterDown()
    }

    // Two staggered rings on one clock: an outer ring that expands and a thin
    // inner sweep that chases it. Both are read inside the draw lambda, so the
    // whole capture animation costs zero recompositions (a pure 60 fps draw).
    val pulseProgress = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            pulseProgress.snapTo(0f)
            pulseProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 560, easing = LinearOutSlowInEasing),
            )
        }
    }

    Box(
        modifier = modifier
            .size(CameraSpacing.shutterTouch)
            .semantics { contentDescription = if (busy) "Saving capture" else if (recording) "Stop recording" else if (videoMode) "Start recording" else if (enabled) "Take photo" else "Camera busy or unavailable" }
            .clickable(
                interactionSource = interaction,
                // No ripple: the ring/disc compression is the feedback.
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.capture()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    clip = false
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val boxRadius = size.minDimension / 2f
            val ringWidth = 4.dp.toPx()
            val gap = 3.5.dp.toPx()
            val outerRadius = CameraSpacing.shutterVisual.toPx() / 2f - ringWidth / 2f
            val discRadius = outerRadius - ringWidth / 2f - gap

            // 1. Capture pulse — expands into the empty margin, never outside the box.
            val p = pulseProgress.value
            if (p < 1f) {
                val maxPulse = boxRadius - 1.dp.toPx()

                // Main ring: fast out, quick fade.
                val ringRadius = discRadius + (maxPulse - discRadius) * p
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f * (1f - p)),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = (2f - 0.8f * p).dp.toPx()),
                )

                // Chase ring: starts a beat later, thinner and shorter-lived. It is
                // what makes the press read as a "snap" rather than a slow bloom.
                val chase = ((p - 0.18f) / 0.82f).coerceIn(0f, 1f)
                if (chase > 0f) {
                    val chaseRadius = discRadius + (maxPulse - discRadius) * chase
                    drawCircle(
                        color = Color.White.copy(alpha = 0.28f * (1f - chase)),
                        radius = chaseRadius,
                        center = center,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }

            // 2. Outer ring — dims slightly while pressed, like the iPhone shutter.
            drawCircle(
                color = Color.White.copy(alpha = if (pressScale < 0.99f) 0.7f else .9f),
                radius = outerRadius,
                center = center,
                style = Stroke(width = ringWidth),
            )

            val radius = discRadius * (1f - 0.16f * busyAmount) *
                (1f - 0.04f * (1f - pulseProgress.value).coerceIn(0f, 1f))
            val halfSize = radius * (1f - 0.48f * recordingShape)
            drawRoundRect(
                color = discColor.copy(alpha = enabledAmount * .10f),
                topLeft = Offset(center.x - halfSize, center.y - halfSize),
                size = Size(halfSize * 2f, halfSize * 2f),
                cornerRadius = CornerRadius(radius * (1f - 0.88f * recordingShape)),
            )
            drawRoundRect(
                brush = grain, alpha = .22f * enabledAmount,
                topLeft = Offset(center.x - halfSize, center.y - halfSize),
                size = Size(halfSize * 2f, halfSize * 2f),
                cornerRadius = CornerRadius(radius * (1f - 0.88f * recordingShape)),
            )
            if (videoMode) drawRoundRect(
                color = CameraColors.Red.copy(alpha = .9f * enabledAmount),
                topLeft = Offset(center.x - halfSize, center.y - halfSize),
                size = Size(halfSize * 2f, halfSize * 2f),
                cornerRadius = CornerRadius(radius * (1f - 0.88f * recordingShape)),
                style = Stroke(2.dp.toPx()),
            )

        }
        // Real pending work, not a fabricated percentage. The spinner exists
        // only while capture/save is in flight and cannot enable the shutter.
        AnimatedVisibility(visible = busy, enter = fadeIn(tween(100)), exit = fadeOut(tween(160))) {
            CircularProgressIndicator(
                modifier = Modifier.size(CameraSpacing.shutterVisual + 8.dp),
                color = CameraColors.Accent,
                trackColor = Color.Transparent,
                strokeWidth = 1.5.dp,
            )
        }
    }
}
