package com.zenith.auxcam.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.DeviceProfile
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Radial zoom wheel — **read-only**.
 *
 * Zoom is driven exclusively by pinching the viewfinder; there is no drag on the
 * wheel and no sideways swipe, so this composable has no pointer input at all. It
 * is a live read-out of the engine's zoom, anchored so its arc sits on top of the
 * 0.6× / 1× / 2× chips and unfurls out of them (see [com.zenith.auxcam.ui.CameraScreen]).
 *
 * Visual contract:
 *  • only the **top arc** carries ticks (202°→338°), alpha shaped by `sin()` so
 *    both ends dissolve and the bottom of the circle stays empty;
 *  • the backdrop is a radial gradient that reaches fully transparent before the
 *    bottom edge — no disc, no rim;
 *  • a yellow thread tracks the travelled portion, with a needle and glow knob at
 *    the current stop; the callout pops each time the wheel lands on a chip.
 */
@Composable
fun ZoomDial(
    zoom: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 208.dp,
) {
    val stops = DeviceProfile.ZOOM_STOPS
    val lo = stops.first()
    val hi = stops.last()
    val span = (hi - lo).takeIf { it > 0f } ?: 1f

    val position by animateFloatAsState(
        targetValue = ((zoom - lo) / span).coerceIn(0f, 1f),
        animationSpec = CameraMotion.wheel(),
        label = "wheel-position",
    )

    // Callout pop when the wheel lands on a stop.
    val nearestIndex = nearestStopIndex(zoom)
    val pop = remember { Animatable(1f) }
    var lastIndex by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(nearestIndex) {
        if (lastIndex >= 0f && nearestIndex != lastIndex.toInt()) {
            pop.snapTo(1f)
            pop.animateTo(
                targetValue = 1.1f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessHigh),
            )
            pop.animateTo(1f, CameraMotion.release())
        }
        lastIndex = nearestIndex.toFloat()
    }

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val tickOuter = radius - 3.dp.toPx()

            // Backdrop: bright at the arc, gone before the bottom edge.
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to CameraColors.GlassStrong,
                        0.50f to CameraColors.GlassStrong.copy(alpha = 0.55f),
                        0.80f to CameraColors.GlassStrong.copy(alpha = 0.10f),
                        1.00f to Color.Transparent,
                    ),
                    center = Offset(center.x, center.y - radius * 0.34f),
                    radius = radius * 1.3f,
                ),
                radius = radius,
                center = center,
            )

            // Top arc of ticks.
            for (i in 0..TICKS) {
                val t = i / TICKS.toFloat()
                val falloff = sin(t * Math.PI).toFloat().coerceIn(0f, 1f)
                if (falloff <= 0.02f) continue

                val major = i % 6 == 0
                val length = (if (major) 9f else 4.5f).dp.toPx()
                val angle = Math.toRadians((START_DEGREES + SWEEP_DEGREES * t).toDouble())
                val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(
                    color = Color.White.copy(alpha = 0.72f * falloff * if (major) 1f else 0.55f),
                    start = center + dir * (tickOuter - length),
                    end = center + dir * tickOuter,
                    strokeWidth = (if (major) 2f else 1.3f).dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            val arcRadius = tickOuter - 13.dp.toPx()

            // Travelled thread.
            if (position > 0.001f) {
                drawArc(
                    color = CameraColors.Yellow.copy(alpha = 0.55f),
                    startAngle = START_DEGREES,
                    sweepAngle = SWEEP_DEGREES * position,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Needle + glow knob.
            val needleAngle = Math.toRadians((START_DEGREES + SWEEP_DEGREES * position).toDouble())
            val dir = Offset(cos(needleAngle).toFloat(), sin(needleAngle).toFloat())
            val tip = center + dir * (tickOuter - 1.dp.toPx())
            val fade = sin(position * Math.PI).toFloat()

            drawLine(
                color = CameraColors.Yellow.copy(alpha = 0.35f + 0.65f * fade),
                start = center + dir * (arcRadius - 14.dp.toPx()),
                end = tip,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = CameraColors.Yellow.copy(alpha = 0.22f * fade),
                radius = 8.dp.toPx(),
                center = tip,
            )
            drawCircle(
                color = CameraColors.Yellow.copy(alpha = 0.55f + 0.45f * fade),
                radius = 3.2.dp.toPx(),
                center = tip,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset(y = (diameter * 0.16f))
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

/** Top arc: starts over the left shoulder, sweeps across the top to the right. */
private const val START_DEGREES = 202f
private const val SWEEP_DEGREES = 136f
private const val TICKS = 48

/** Index of the closest entry in [DeviceProfile.ZOOM_STOPS]. */
internal fun nearestStopIndex(zoom: Float): Int {
    var best = 0
    var bestDelta = Float.MAX_VALUE
    DeviceProfile.ZOOM_STOPS.forEachIndexed { index, stop ->
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
