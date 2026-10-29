package com.zenith.auxcam.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.DeviceProfile
import com.zenith.auxcam.ui.feedback.rememberCameraHaptics
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Radial zoom wheel, Apple style.
 *
 * Differences from a "full dial":
 *  • Only the **top arc** carries ticks. Their alpha is `sin`-shaped across the
 *    sweep, so the marks fade out before the sides and there is nothing at the
 *    bottom at all — the wheel dissolves into the scene instead of sitting on it
 *    as a disc.
 *  • The backdrop is a radial gradient anchored at the arc, fading to fully
 *    transparent at the bottom edge. No hard circle, no visible rim.
 *  • The yellow indicator is a spring-driven needle plus a glow knob that lands
 *    on the ticks, and it snaps to the nearest lens stop on release.
 *  • Every detent fires the light `tick()` haptic and the leaf text pops when the
 *    wheel crosses a stop, which is what makes it feel like a real lens barrel.
 *
 * Drag anywhere on the wheel; the angle is what matters, exactly like Apple's.
 */
@Composable
fun ZoomDial(
    zoom: Float,
    onFraction: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 244.dp,
) {
    val stops = DeviceProfile.ZOOM_STOPS
    val lo = stops.first()
    val hi = stops.last()
    val span = (hi - lo).takeIf { it > 0f } ?: 1f

    val haptics = rememberCameraHaptics()

    // Continuous position of the indicator (0..1 along the arc).
    val target = ((zoom - lo) / span).coerceIn(0f, 1f)
    val position by animateFloatAsState(
        targetValue = target,
        animationSpec = CameraMotion.wheel(),
        label = "wheel-position",
    )

    // Leaf text pop whenever the wheel lands on a stop.
    val nearestIndex = nearestStopIndex(zoom)
    val pop = remember { Animatable(1f) }
    var lastIndex by remember { mutableFloatStateOf(nearestIndex.toFloat()) }
    LaunchedEffect(nearestIndex) {
        if (nearestIndex != lastIndex.toInt() && lastIndex >= 0f) {
            pop.snapTo(1f)
            pop.animateTo(
                targetValue = 1.12f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessHigh),
            )
            pop.animateTo(1f, CameraMotion.release())
        }
        lastIndex = nearestIndex.toFloat()
    }

    // Drag state: the fraction this gesture has reached (kept locally so a fast
    // spin never reads back its own animated value), plus the last detent ticked.
    var lastAngle by remember { mutableFloatStateOf(Float.NaN) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastDetent by remember { mutableFloatStateOf(Float.NaN) }

    // 48 detents across the arc ≈ one tick every 3.4°.
    val detents = 48

    Box(
        modifier = modifier
            .size(diameter)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        lastAngle = angleOf(offset, c)
                        dragFraction = target
                        lastDetent = (dragFraction * detents).roundToInt().toFloat()
                    },
                    onDragEnd = {
                        lastAngle = Float.NaN
                        haptics.gestureEnd()
                        // Snap to the nearest lens stop, like a clicked aperture ring.
                        onCommit(stops[nearestStopIndex(target)])
                    },
                    onDragCancel = { lastAngle = Float.NaN },
                    onDrag = { change, _ ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val current = angleOf(change.position, c)
                        if (!lastAngle.isNaN()) {
                            var delta = current - lastAngle
                            if (delta > 180f) delta -= 360f
                            if (delta < -180f) delta += 360f
                            // Counter-clockwise across the top raises the zoom.
                            dragFraction = (dragFraction - delta / SWEEP_DEGREES).coerceIn(0f, 1f)
                            val next = dragFraction
                            onFraction(next)

                            // Haptic detent: only when the wheel crosses a tick.
                            val detent = (next * detents).roundToInt().toFloat()
                            if (detent != lastDetent) {
                                lastDetent = detent
                                haptics.tick()
                            }
                        }
                        lastAngle = current
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val tickOuter = radius - 4.dp.toPx()

            // ── backdrop: fades out toward the bottom, no hard edge ──────────
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to CameraColors.GlassStrong,
                        0.55f to CameraColors.GlassStrong.copy(alpha = 0.55f),
                        0.85f to CameraColors.GlassStrong.copy(alpha = 0.12f),
                        1.00f to Color.Transparent,
                    ),
                    center = Offset(center.x, center.y - radius * 0.30f),
                    radius = radius * 1.35f,
                ),
                radius = radius,
                center = center,
            )

            // ── top arc: 48 ticks, alpha sin-shaped so both ends vanish ──────
            val ticks = detents
            for (i in 0..ticks) {
                val t = i / ticks.toFloat()
                val angleDeg = START_DEGREES + SWEEP_DEGREES * t
                // Fade to zero at both ends: the bottom of the circle is empty.
                val falloff = sin(t * Math.PI).toFloat().coerceIn(0f, 1f)
                if (falloff <= 0.02f) continue

                val major = i % 6 == 0
                val length = (if (major) 10f else 5f).dp.toPx()
                val angle = Math.toRadians(angleDeg.toDouble())
                val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                val outer = center + dir * tickOuter
                val inner = center + dir * (tickOuter - length)

                drawLine(
                    color = Color.White.copy(alpha = 0.72f * falloff * if (major) 1f else 0.55f),
                    start = inner,
                    end = outer,
                    strokeWidth = (if (major) 2f else 1.3f).dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // ── travelled arc: a thin yellow thread over the ticks ───────────
            val startAngle = START_DEGREES + SWEEP_DEGREES * position
            val arcRadius = tickOuter - 15.dp.toPx()
            drawArc(
                color = CameraColors.Yellow.copy(alpha = 0.55f),
                startAngle = START_DEGREES,
                sweepAngle = SWEEP_DEGREES * position,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )

            // ── indicator: needle + glow knob at the arc position ────────────
            val needleAngle = Math.toRadians(startAngle.toDouble())
            val dir = Offset(cos(needleAngle).toFloat(), sin(needleAngle).toFloat())
            val inner = center + dir * (arcRadius - 16.dp.toPx())
            val outer = center + dir * (tickOuter - 1.dp.toPx())
            val falloffAtNeedle = sin(position * Math.PI).toFloat()

            drawLine(
                color = CameraColors.Yellow.copy(alpha = 0.35f + 0.65f * falloffAtNeedle),
                start = inner,
                end = outer,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = CameraColors.Yellow.copy(alpha = 0.22f * falloffAtNeedle),
                radius = 9.dp.toPx(),
                center = outer,
            )
            drawCircle(
                color = CameraColors.Yellow.copy(alpha = 0.55f + 0.45f * falloffAtNeedle),
                radius = 3.4.dp.toPx(),
                center = outer,
            )
        }

        Box(contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // Sits a little high so it clears the arc's bottom fade.
                modifier = Modifier
                    .offset(y = (-10).dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                        clip = false
                    },
            ) {
                Text(
                    text = formatZoom(zoom),
                    style = CameraType.Dial,
                    color = CameraColors.Label,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "${(DeviceProfile.FOCAL_MAIN_MM * zoom).roundToInt()} MM",
                    style = CameraType.DialCaption,
                    color = CameraColors.LabelTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Top arc: starts above the left shoulder, sweeps over the top to the right. */
private const val START_DEGREES = 202f
private const val SWEEP_DEGREES = 136f

private fun nearestStopIndex(zoom: Float): Int {
    val stops = DeviceProfile.ZOOM_STOPS
    var best = 0
    var bestDelta = Float.MAX_VALUE
    stops.forEachIndexed { index, stop ->
        val delta = abs(stop - zoom)
        if (delta < bestDelta) {
            bestDelta = delta
            best = index
        }
    }
    return best
}

private fun formatZoom(zoom: Float): String =
    if (abs(zoom - zoom.roundToInt()) < 0.05f) "${zoom.roundToInt()}×" else String.format("%.1f×", zoom)

private fun angleOf(position: Offset, c: Offset): Float =
    Math.toDegrees(atan2((position.y - c.y).toDouble(), (position.x - c.x).toDouble())).toFloat()
