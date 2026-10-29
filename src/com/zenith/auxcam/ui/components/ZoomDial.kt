package com.zenith.auxcam.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.DeviceProfile
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Radial zoom dial (drag around the rim, like a lens barrel).
 *
 * The whole 240 dp dial is composed inside an overlay that sits above the
 * viewfinder column, so it can scale in and out without ever being clipped by
 * the 4:3 preview box or the bottom bar.
 */
@Composable
fun ZoomDial(
    zoom: Float,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 236.dp,
    onRotate: (deltaDegrees: Float) -> Unit,
) {
    var lastAngle by remember { mutableFloatStateOf(Float.NaN) }
    val needle by animateFloatAsState(
        targetValue = ((zoom - DeviceProfile.ZOOM_STOPS.first()) /
            (DeviceProfile.ZOOM_STOPS.last() - DeviceProfile.ZOOM_STOPS.first())).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "zoom-needle",
    )

    Box(
        modifier = modifier
            .size(diameter)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        lastAngle = angleOf(offset, size.width / 2f, size.height / 2f)
                    },
                    onDragEnd = { lastAngle = Float.NaN },
                    onDragCancel = { lastAngle = Float.NaN },
                    onDrag = { change, _ ->
                        val current = angleOf(change.position, size.width / 2f, size.height / 2f)
                        if (!lastAngle.isNaN()) {
                            var delta = current - lastAngle
                            if (delta > 180f) delta -= 360f
                            if (delta < -180f) delta += 360f
                            onRotate(delta)
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

            // Glass disk.
            drawCircle(color = CameraColors.GlassStrong, radius = radius, center = center)
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )

            // 48 notched ticks.
            val tickOuter = radius - 6.dp.toPx()
            for (i in 0 until 48) {
                val angle = Math.toRadians((i * 360f / 48f).toDouble())
                val major = i % 6 == 0
                val cardinal = i % 12 == 0
                val length = when {
                    cardinal -> 11.dp.toPx()
                    major -> 7.5.dp.toPx()
                    else -> 4.5.dp.toPx()
                }
                val start = Offset(
                    center.x + (cos(angle) * (tickOuter - length)).toFloat(),
                    center.y + (sin(angle) * (tickOuter - length)).toFloat(),
                )
                val end = Offset(
                    center.x + (cos(angle) * tickOuter).toFloat(),
                    center.y + (sin(angle) * tickOuter).toFloat(),
                )
                drawLine(
                    color = Color.White.copy(alpha = if (major) 0.85f else 0.24f),
                    start = start,
                    end = end,
                    strokeWidth = if (major) 2.2.dp.toPx() else 1.4.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }

            // Yellow pointer sweeping the top 160°.
            val needleAngle = Math.toRadians((270f + (needle - 0.5f) * 160f).toDouble())
            val inner = radius - 20.dp.toPx()
            val outer = tickOuter - 2.dp.toPx()
            drawLine(
                color = CameraColors.Yellow,
                start = Offset(
                    center.x + (cos(needleAngle) * inner).toFloat(),
                    center.y + (sin(needleAngle) * inner).toFloat(),
                ),
                end = Offset(
                    center.x + (cos(needleAngle) * outer).toFloat(),
                    center.y + (sin(needleAngle) * outer).toFloat(),
                ),
                strokeWidth = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatZoom(zoom),
                style = CameraType.Dial,
                color = CameraColors.Label,
                textAlign = TextAlign.Center,
            )
            Text(
                text = String.format("%d MM", (DeviceProfile.FOCAL_MAIN_MM * zoom).roundToInt()),
                style = CameraType.DialCaption,
                color = CameraColors.LabelTertiary.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun formatZoom(zoom: Float): String = String.format("%.1f×", zoom)

private fun angleOf(position: Offset, cx: Float, cy: Float): Float =
    Math.toDegrees(atan2((position.y - cy).toDouble(), (position.x - cx).toDouble())).toFloat()
