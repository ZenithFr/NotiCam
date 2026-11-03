package com.zenith.auxcam.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.auxcam.ui.effects.cameraGlass
import com.zenith.auxcam.DeviceProfile
import com.zenith.auxcam.ui.feedback.rememberCameraHaptics
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraType
import kotlin.math.abs

/**
 * 0.6× / 1× / 2× selector with an Apple-style travelling indicator.
 *
 * Instead of every chip individually fading its own background (which reads as
 * three unrelated blinks), a single white disc *slides* between positions on a
 * spring while the labels cross-fade their colour. The chip under the finger
 * still compresses.
 *
 * `zoom` is the live engine value, so pinching the viewfinder moves the disc too
 * — the control never lies about what the camera is doing. While the pinch sits
 * between two stops the disc becomes a faint traveling guide and the nearest label brightens, which
 * avoids the "flicker between two chips" artefact.
 */
@Composable
fun ZoomChips(
    zoom: Float,
    onSelect: (Float) -> Unit,
    stops: List<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    chipSize: Dp = 48.dp,
    gap: Dp = 0.dp,
) {
    if (stops.isEmpty()) return
    val haptics = rememberCameraHaptics()
    val safeZoom = zoom.coerceAtLeast(stops.first())
    val nearest = stops.indices.minByOrNull { abs(kotlin.math.ln(safeZoom / stops[it])) } ?: 0
    val onStop = abs(kotlin.math.ln(safeZoom / stops[nearest])) < 0.08f

    val discScale by animateFloatAsState(
        targetValue = if (onStop) 1f else 0.84f,
        animationSpec = CameraMotion.select(),
        label = "chip-disc-scale",
    )
    val discAlpha by animateFloatAsState(
        targetValue = if (onStop) 1f else 0.16f,
        animationSpec = CameraMotion.fade(if (onStop) 120 else 180),
        label = "chip-disc-alpha",
    )
    // Follow the live zoom continuously between stops in log space. Only the
    // presentation is smoothed; camera commands are never delayed or replayed.
    val position = if (stops.size > 1) {
        val lower = stops.indexOfLast { it <= zoom }.coerceIn(0, stops.size - 2)
        val fraction = (kotlin.math.ln(zoom.coerceAtLeast(stops.first()) / stops[lower]) /
            kotlin.math.ln(stops[lower + 1] / stops[lower])).coerceIn(0f, 1f)
        lower + fraction
    } else 0f
    val discX by animateDpAsState(
        targetValue = (chipSize + gap) * position,
        animationSpec = CameraMotion.fluid(),
        label = "chip-disc-x",
    )

    // Detent haptic when the selection lands somewhere new — but not on first draw.
    var announced by remember { mutableStateOf(false) }
    LaunchedEffect(nearest, onStop) {
        if (!announced) {
            announced = true
        } else if (onStop) {
            haptics.tick()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        // Travelling selection disc, behind the labels.
        Box(
            modifier = Modifier
                .offset(x = discX)
                .size(chipSize)
                .padding(8.dp) // 32dp visible selector; 48dp hit target is unchanged.
                .graphicsLayer {
                    scaleX = discScale
                    scaleY = discScale
                    alpha = discAlpha
                    clip = false
                }
                .background(Color.White, CircleShape),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stops.forEachIndexed { index, stop ->
                ZoomChip(
                    label = DeviceProfile.zoomLabel(stop),
                    caption = DeviceProfile.zoomCaption(stop),
                    selected = index == nearest && onStop,
                    dimmed = !onStop && index == nearest,
                    size = chipSize,
                    enabled = enabled,
                    onClick = {
                        haptics.select()
                        onSelect(stop)
                    },
                )
            }
        }
    }
}

@Composable
private fun ZoomChip(
    label: String,
    caption: String?,
    selected: Boolean,
    dimmed: Boolean,
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "chip-press",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = when {
            selected -> 1f
            dimmed -> 0.9f
            else -> 0.85f
        },
        animationSpec = CameraMotion.fade(180),
        label = "chip-label-alpha",
    )
    // The travelling disc is white; the label on it must be dark to read.
    val labelColor by animateColorAsState(
        targetValue = if (selected) CameraColors.OnLight else Color.White,
        animationSpec = CameraMotion.select(), label = "zoom-label-color",
    )

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = false
            }
            // Shared glass body lives on the containing capsule. No nested rims or grain.
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Two-line chip: the UW chip reads "UW" with "0.6×" beneath it.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = CameraType.Lens,
                fontSize = 12.sp,
                color = labelColor.copy(alpha = labelAlpha),
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = CameraType.Lens,
                    fontSize = 8.sp,
                    color = labelColor.copy(alpha = labelAlpha * 0.7f),
                )
            }
        }
    }
}

