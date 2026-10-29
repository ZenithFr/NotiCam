package com.zenith.auxcam.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraController
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.CaptureMode
import com.zenith.auxcam.FlashMode
import com.zenith.auxcam.FrameRatio
import com.zenith.auxcam.Resolution
import com.zenith.auxcam.ui.effects.matteGlass
import com.zenith.auxcam.ui.effects.rememberMatteGrain
import com.zenith.auxcam.ui.feedback.rememberCameraHaptics
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraSpacing
import com.zenith.auxcam.ui.theme.CameraType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Glass icon button.
 *
 * The 44 dp circle lives inside a 48 dp touch target and only the circle is
 * scaled on press (`clip = false`), so the glyph is never cropped by the
 * neighbouring layout while the animation runs.
 */
@Composable
fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = CameraColors.Label,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberCameraHaptics()
    val grain = rememberMatteGrain()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        // Down: crisp. Up: a touch of overshoot. This asymmetry is the whole trick.
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "glass-press",
    )
    val background by animateColorAsState(
        targetValue = if (selected) Color.White else CameraColors.Glass,
        animationSpec = tween(180),
        label = "glass-bg",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) Color.Black else tint,
        animationSpec = tween(180),
        label = "glass-fg",
    )

    Box(
        modifier = modifier
            .size(CameraSpacing.touch)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.tap()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    clip = false
                }
                .background(background, CircleShape)
                .border(0.5.dp, CameraColors.Separator, CircleShape)
                .matteGlass(grain, CircleShape, strength = if (selected) 0.35f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = foreground,
                modifier = Modifier.size(CameraSpacing.iconSize),
            )
        }
    }
}

/** Shizuku / session / capture state in one quiet capsule. */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 30.dp)
            .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(120)) },
            label = "status-pill",
        ) { value ->
            Text(
                text = value,
                style = CameraType.Caption,
                color = CameraColors.LabelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 1× / 2× / 5× lens selector. */
@Composable
fun LensChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "chip-press",
    )
    val background by animateColorAsState(
        targetValue = if (selected) Color.White else CameraColors.Glass,
        animationSpec = tween(200),
        label = "chip-bg",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) Color.Black else Color.White,
        animationSpec = tween(200),
        label = "chip-fg",
    )

    Box(
        modifier = modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = false
            }
            .background(background, CircleShape)
            .border(0.5.dp, CameraColors.Separator, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = CameraType.Lens, color = foreground)
    }
}

/** PHOTO · PORTRAIT · PRO, with the iOS yellow selection. */
@Composable
fun ModeCarousel(
    selected: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CaptureMode.entries.forEach { mode ->
            ModeLabel(
                label = mode.label,
                selected = mode == selected,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun ModeLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberCameraHaptics()
    val color by animateColorAsState(
        targetValue = if (selected) CameraColors.Yellow else CameraColors.LabelTertiary,
        animationSpec = tween(220),
        label = "mode-color",
    )
    val scale by animateFloatAsState(
        // Selected labels sit a hair larger — Apple does the same in the carousel.
        targetValue = when {
            pressed -> 0.92f
            selected -> 1.06f
            else -> 1f
        },
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "mode-scale",
    )

    Box(
        modifier = Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.select()
                    onClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = CameraType.Mode,
            color = color,
            maxLines = 1,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin.Center
                clip = false
            },
        )
    }
}

/** iOS-style thin slider with a white knob. */
@Composable
fun IosSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val view = LocalView.current
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    // Continuous: the value follows the finger with no snapping, which is what
    // makes the aperture and exposure sliders feel like the iOS ones.
    fun emit(x: Float) {
        val raw = valueRange.start + (x / widthPx).coerceIn(0f, 1f) * span
        onValueChange(raw.coerceIn(valueRange.start, valueRange.endInclusive))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(valueRange) {
                detectTapGestures { offset -> emit(offset.x) }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { change, _ ->
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    emit(change.position.x)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val trackHeight = 4.dp.toPx()
            val radius = CornerRadius(trackHeight / 2f)
            val knobRadius = 10.dp.toPx()
            val travel = (size.width - knobRadius * 2f).coerceAtLeast(1f)
            val knobX = knobRadius + travel * fraction

            drawRoundRect(
                color = CameraColors.SliderTrack,
                topLeft = Offset(knobRadius, cy - trackHeight / 2f),
                size = Size(travel, trackHeight),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = CameraColors.SliderFill,
                topLeft = Offset(knobRadius, cy - trackHeight / 2f),
                size = Size((travel * fraction).coerceAtLeast(0f), trackHeight),
                cornerRadius = radius,
            )
            // Knob halo + body.
            drawCircle(color = Color.Black.copy(alpha = 0.18f), radius = knobRadius + 2.dp.toPx(), center = Offset(knobX, cy))
            drawCircle(color = Color.White, radius = knobRadius, center = Offset(knobX, cy))
        }
    }
}

/** White-when-active capsule used inside the Pro sheet. */
@Composable
fun TogglePill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "toggle-press",
    )
    val background by animateColorAsState(
        targetValue = if (active) Color.White else CameraColors.Glass,
        animationSpec = tween(180),
        label = "toggle-bg",
    )
    val foreground by animateColorAsState(
        targetValue = if (active) Color.Black else Color.White,
        animationSpec = tween(180),
        label = "toggle-fg",
    )
    val haptics = rememberCameraHaptics()
    Box(
        modifier = modifier
            .height(CameraSpacing.capsuleHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = false
            }
            .background(background, RoundedCornerShape(CameraSpacing.capsuleHeight / 2))
            .border(0.5.dp, CameraColors.Separator, RoundedCornerShape(CameraSpacing.capsuleHeight / 2))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.select()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = CameraType.Value, color = foreground, fontSize = 12.sp)
    }
}

/** 52 dp gallery tile; shows the real captured bokeh frame once available. */
@Composable
fun GalleryTile(
    thumbnail: ImageBitmap?,
    pulse: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val grain = rememberMatteGrain()
    val haptics = rememberCameraHaptics()

    // Landing: the tile pops and a white flash washes over it the moment the
    // capture arrives, so the shutter -> tile flight has a visible destination.
    val landing = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            landing.snapTo(1f)
            landing.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
        }
    }
    val pressed = remember { MutableInteractionSource() }
    val isPressed by pressed.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = if (isPressed) CameraMotion.press() else CameraMotion.release(),
        label = "gallery-press",
    )

    Box(
        modifier = modifier
            .size(CameraSpacing.gallery)
            .graphicsLayer {
                val pop = 1f + 0.10f * landing.value
                scaleX = pressScale * pop
                scaleY = pressScale * pop
                clip = false
            }
            .clip(shape)
            .background(CameraColors.Label.copy(alpha = 0.08f), shape)
            .border(0.5.dp, CameraColors.SeparatorStrong, shape)
            .matteGlass(grain, shape, strength = 0.8f)
            .clickable(
                interactionSource = pressed,
                indication = null,
                onClick = {
                    haptics.tap()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            androidx.compose.foundation.Image(
                bitmap = thumbnail,
                contentDescription = "Last photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = NotiIcons.Gallery,
                contentDescription = "Gallery",
                tint = CameraColors.LabelTertiary,
                modifier = Modifier.size(22.dp),
            )
        }

        // White flash on arrival, fading over 420 ms.
        if (landing.value > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = landing.value * 0.85f }
                    .background(Color.White),
            )
        }
    }
}

/**
 * The Pro sheet: aperture ladder, exposure and the shoot toggles.
 *
 * The panel itself is static — [BottomBar] performs the slide-in on the whole
 * panel (translation + fade + a 4 % scale) so the contents are never re-laid out
 * frame by frame. The only thing that morphs inside is the aperture block, which
 * belongs to Portrait mode.
 */
@Composable
fun ProSheet(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    val grain = rememberMatteGrain()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CameraColors.GlassStrong, RoundedCornerShape(CameraSpacing.panelRadius))
            .border(
                width = 0.5.dp,
                color = CameraColors.SeparatorStrong,
                shape = RoundedCornerShape(CameraSpacing.panelRadius),
            )
            .matteGlass(grain, RoundedCornerShape(CameraSpacing.panelRadius))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        // ── Framing: 1:1 · 4:3 · 16:9 · Full ────────────────────────────────
        Text("FRAMING", style = CameraType.Section, color = CameraColors.LabelTertiary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameRatio.entries.forEach { ratio ->
                SegmentPill(
                    label = ratio.label,
                    selected = state.frameRatio == ratio,
                    onClick = { actions.onFrameRatioSelected(ratio) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Aperture: Portrait and Pro only ────────────────────────────────
        AnimatedVisibility(
            visible = state.apertureVisible,
            enter = fadeIn(tween(200)) + slideInVertically(
                animationSpec = CameraMotion.slide<IntOffset>(),
                initialOffsetY = { -it / 3 },
            ),
            exit = fadeOut(tween(120)) + slideOutVertically(
                animationSpec = tween<IntOffset>(140),
                targetOffsetY = { -it / 4 },
            ),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = NotiIcons.Aperture,
                        contentDescription = "Aperture",
                        tint = CameraColors.LabelSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("APERTURE", style = CameraType.Section, color = CameraColors.LabelTertiary)
                    Spacer(Modifier.weight(1f))
                    Text(state.fStopLabel, style = CameraType.Value, color = CameraColors.Label)
                }
                IosSlider(
                    value = state.apertureFraction,
                    valueRange = 0f..1f,
                    onValueChange = { actions.onApertureChanged(fStopForFraction(it)) },
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("1.4", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("2.8", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("5.6", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("11", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("16", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        // ── Exposure ───────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EXPOSURE", style = CameraType.Section, color = CameraColors.LabelTertiary)
            Spacer(Modifier.weight(1f))
            Text(state.evLabel, style = CameraType.Value, color = CameraColors.Label)
        }
        IosSlider(
            value = state.evFraction,
            valueRange = 0f..1f,
            onValueChange = { actions.onEvChanged(it * 8f - 4f) },
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("−4", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
            Spacer(Modifier.weight(1f))
            Text("0", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
            Spacer(Modifier.weight(1f))
            Text("+4", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CameraColors.Separator),
        )
        Spacer(Modifier.height(14.dp))

        // ── Capture options ────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TogglePill(
                label = state.resolution.label,
                active = state.resolution == Resolution.High,
                onClick = { actions.onResolutionToggle() },
                modifier = Modifier.weight(1f),
            )
            TogglePill(
                label = "GRID",
                active = state.grid,
                onClick = { actions.onGridToggle() },
                modifier = Modifier.weight(1f),
            )
            TogglePill(
                label = if (state.timerSeconds == 0) "TIMER" else "${state.timerSeconds}s",
                active = state.timerSeconds > 0,
                onClick = { actions.onTimerCycle() },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        // Telephoto assist: above 2x hand the session to the physical tele lens.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TelephotoPill(
                active = state.telephoto,
                onClick = { actions.onTelephotoToggle() },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = state.telemetry,
            style = CameraType.Telemetry,
            color = CameraColors.LabelTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Segmented control used for the framing row. */
@Composable
private fun SegmentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberCameraHaptics()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "segment-press",
    )
    val bg by animateColorAsState(
        targetValue = if (selected) Color.White else CameraColors.Glass,
        animationSpec = tween(190),
        label = "segment-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) Color.Black else CameraColors.LabelSecondary,
        animationSpec = tween(190),
        label = "segment-fg",
    )

    Box(
        modifier = modifier
            .height(32.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = false
            }
            .background(bg, RoundedCornerShape(16.dp))
            .border(0.5.dp, CameraColors.Separator, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.select()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CameraType.Caption, color = fg, maxLines = 1)
    }
}

/**
 * Telephoto toggle with the "camera with a long lens" pictogram, so it reads as a
 * lens choice rather than another abstract switch.
 */
@Composable
private fun TelephotoPill(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberCameraHaptics()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "tele-press",
    )
    val bg by animateColorAsState(
        targetValue = if (active) Color.White else CameraColors.Glass,
        animationSpec = tween(190),
        label = "tele-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) Color.Black else CameraColors.LabelSecondary,
        animationSpec = tween(190),
        label = "tele-fg",
    )

    Row(
        modifier = modifier
            .height(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = false
            }
            .background(bg, RoundedCornerShape(18.dp))
            .border(0.5.dp, CameraColors.Separator, RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.select()
                    onClick()
                },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TelephotoGlyph(tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (active) "TELE · >2×" else "TELE OFF",
            style = CameraType.Caption,
            color = fg,
            maxLines = 1,
        )
    }
}

/** Camera body with a long barrel — drawn, not themed, so it tints cleanly. */
@Composable
private fun TelephotoGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.4.dp.toPx()
        // body
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.04f, h * 0.28f),
            size = Size(w * 0.52f, h * 0.44f),
            cornerRadius = CornerRadius(h * 0.10f),
            style = Stroke(width = stroke),
        )
        // barrel
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.56f, h * 0.38f),
            size = Size(w * 0.34f, h * 0.24f),
            cornerRadius = CornerRadius(h * 0.05f),
            style = Stroke(width = stroke),
        )
        // hood
        drawLine(
            color = tint,
            start = Offset(w * 0.90f, h * 0.34f),
            end = Offset(w * 0.90f, h * 0.66f),
            strokeWidth = stroke * 1.6f,
        )
        // viewfinder hump
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.16f, h * 0.16f),
            size = Size(w * 0.18f, h * 0.14f),
            cornerRadius = CornerRadius(h * 0.04f),
            style = Stroke(width = stroke),
        )
    }
}

/** Inverse of [CameraUiState.apertureFraction]: 0..1 → f-number on a log scale. */
private fun fStopForFraction(fraction: Float): Float {
    val lo = CameraController.FSTOPS.first().toDouble()
    val hi = CameraController.FSTOPS.last().toDouble()
    val f = fraction.coerceIn(0f, 1f).toDouble()
    return (lo * Math.pow(hi / lo, f)).toFloat()
}

/** Portrait-only quick aperture, mirroring the iPhone's in-toolbar control. */
@Composable
fun QuickApertureRow(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = NotiIcons.Aperture,
            contentDescription = null,
            tint = CameraColors.LabelSecondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            IosSlider(
                value = state.apertureFraction,
                valueRange = 0f..1f,
                onValueChange = { actions.onApertureChanged(fStopForFraction(it)) },
            )
        }
        Text(
            text = state.fStopLabel,
            style = CameraType.Value,
            color = CameraColors.Label,
            modifier = Modifier.width(56.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** Top chrome: flash, status, Pro. Laid out with a Box so the pill stays centred. */
@Composable
fun TopBar(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
    onInteraction: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = CameraSpacing.gutter),
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassIconButton(
                icon = when (state.flash) {
                    FlashMode.Off -> NotiIcons.FlashOff
                    FlashMode.Auto -> NotiIcons.FlashAuto
                    FlashMode.On -> NotiIcons.FlashOn
                },
                contentDescription = "Flash",
                onClick = {
                    onInteraction()
                    actions.onFlashClick()
                },
            )

            // RAW lives beside the flash now, not inside the settings sheet.
            RawBadge(
                active = state.raw,
                onClick = {
                    onInteraction()
                    actions.onRawToggle()
                },
            )
        }

        StatusPill(
            text = state.status,
            modifier = Modifier.align(Alignment.Center),
        )

        GlassIconButton(
            icon = if (state.proSheetOpen) NotiIcons.Close else NotiIcons.Sliders,
            contentDescription = "Pro controls",
            onClick = {
                onInteraction()
                actions.onProToggle()
            },
            selected = state.proSheetOpen,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/**
 * RAW toggle: a compact capsule next to the flash button.
 *
 * Wordmarks read faster than an icon here ("RAW" is already the industry
 * shorthand), and the active state uses the same white fill as the other
 * selected controls.
 */
@Composable
fun RawBadge(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberCameraHaptics()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "raw-press",
    )
    val background by animateColorAsState(
        targetValue = if (active) Color.White else CameraColors.Glass,
        animationSpec = tween(180),
        label = "raw-bg",
    )
    val foreground by animateColorAsState(
        targetValue = if (active) Color.Black else CameraColors.LabelSecondary,
        animationSpec = tween(180),
        label = "raw-fg",
    )

    // The "vanity" moment: pressing RAW briefly morphs the badge into a bayer
    // colour-filter-array icon — the rainbow sensor grid from the camera manuals.
    val bayer = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val grain = rememberMatteGrain()

    Box(
        modifier = modifier
            .height(30.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = false
            }
            .background(background, RoundedCornerShape(15.dp))
            .border(0.5.dp, CameraColors.Separator, RoundedCornerShape(15.dp))
            .matteGlass(grain, RoundedCornerShape(15.dp), strength = 0.7f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.select()
                    onClick()
                    scope.launch {
                        bayer.snapTo(0f)
                        bayer.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
                        bayer.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
                    }
                },
            )
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "RAW",
            style = CameraType.Caption,
            color = foreground,
            modifier = Modifier.graphicsLayer { alpha = 1f - bayer.value },
        )

        // Bayer mosaic: 2x2 RGGB blocks repeated, tinted like the manual diagrams,
        // scaling in from the centre and dissolving back into the wordmark.
        if (bayer.value > 0.001f) {
            Canvas(
                Modifier
                    .size(30.dp)
                    .graphicsLayer {
                        val s = 0.55f + 0.45f * bayer.value
                        scaleX = s
                        scaleY = s
                        alpha = bayer.value
                    },
            ) {
                val cells = 4
                val cell = size.minDimension / cells
                val colors = listOf(
                    Color(0xFFFF5A5F), Color(0xFF3DDC84),
                    Color(0xFF3DDC84), Color(0xFF4C9AFF),
                )
                for (row in 0 until cells) {
                    for (col in 0 until cells) {
                        val tint = colors[(row * cells + col) % colors.size]
                        drawRect(
                            color = tint.copy(alpha = 0.85f),
                            topLeft = Offset(col * cell + 1f, row * cell + 1f),
                            size = Size(cell - 2f, cell - 2f),
                        )
                    }
                }
                // Micro-lens grid on top, the way the sensor diagrams show it.
                for (i in 0..cells) {
                    val p = i * cell
                    drawLine(
                        color = Color.Black.copy(alpha = 0.25f),
                        start = Offset(p, 0f),
                        end = Offset(p, size.height),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        color = Color.Black.copy(alpha = 0.25f),
                        start = Offset(0f, p),
                        end = Offset(size.width, p),
                        strokeWidth = 1f,
                    )
                }
            }
        }
    }
}

/** Bottom chrome: Pro sheet, mode carousel and the shutter row. */
@Composable
fun BottomBar(
    state: CameraUiState,
    actions: CameraActions,
    onShutterCenter: (Offset) -> Unit,
    onGalleryCenter: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CameraSpacing.gutterWide),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The Pro menu is NOT built here: it lives in CameraScreen as an overlay
        // anchored to the chrome band, so opening it never changes this Column's
        // height (which would resize the SurfaceView underneath).
        ModeCarousel(
            selected = state.mode,
            onSelect = { actions.onModeSelected(it) },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, start = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GalleryTile(
                thumbnail = state.lastThumbnail,
                pulse = state.galleryPulse,
                onClick = { actions.onGalleryOpen() },
                modifier = Modifier.onGloballyPositioned { coords ->
                    onGalleryCenter(
                        coords.positionInRoot() +
                            Offset(coords.size.width / 2f, coords.size.height / 2f),
                    )
                },
            )

            Spacer(Modifier.weight(1f))

            ShutterButton(
                mode = state.mode,
                recording = false,
                enabled = state.sessionReady,
                pulse = state.capturePulse,
                onClick = { actions.onShutterClick() },
                modifier = Modifier.onGloballyPositioned { coords ->
                    onShutterCenter(
                        coords.positionInRoot() +
                            Offset(coords.size.width / 2f, coords.size.height / 2f),
                    )
                },
            )

            Spacer(Modifier.weight(1f))

            if (state.lensCount > 1) {
                GlassIconButton(
                    icon = NotiIcons.FlipCamera,
                    contentDescription = "Switch lens",
                    onClick = { actions.onSwitchLens() },
                )
            } else {
                // Keeps the shutter optically centred when there is no lens flip.
                Spacer(Modifier.size(CameraSpacing.touch))
            }
        }
    }
}
