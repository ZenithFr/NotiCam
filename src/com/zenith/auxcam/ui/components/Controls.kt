package com.zenith.auxcam.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraController
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.CaptureMode
import com.zenith.auxcam.FlashMode
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraSpacing
import com.zenith.auxcam.ui.theme.CameraType
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
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
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
                indication = LocalIndication.current,
                onClick = onClick,
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
                .border(0.5.dp, CameraColors.Separator, CircleShape),
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
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
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
                indication = LocalIndication.current,
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
    val color by animateColorAsState(
        targetValue = if (selected) CameraColors.Yellow else CameraColors.LabelTertiary,
        animationSpec = tween(220),
        label = "mode-color",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "mode-scale",
    )

    Box(
        modifier = Modifier
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
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
    steps: Int = 0,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val view = LocalView.current
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    fun emit(x: Float) {
        val raw = valueRange.start + (x / widthPx).coerceIn(0f, 1f) * span
        val snapped = if (steps > 0) {
            val step = span / steps
            (raw / step).roundToInt() * step
        } else {
            raw
        }
        onValueChange(snapped.coerceIn(valueRange.start, valueRange.endInclusive))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(valueRange, steps) {
                detectTapGestures { offset -> emit(offset.x) }
            }
            .pointerInput(valueRange, steps) {
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
    Box(
        modifier = modifier
            .height(CameraSpacing.capsuleHeight)
            .background(background, RoundedCornerShape(CameraSpacing.capsuleHeight / 2))
            .border(0.5.dp, CameraColors.Separator, RoundedCornerShape(CameraSpacing.capsuleHeight / 2))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(CameraSpacing.gallery)
            .clip(shape)
            .background(CameraColors.Label.copy(alpha = 0.08f), shape)
            .border(0.5.dp, CameraColors.SeparatorStrong, shape)
            .clickable(onClick = onClick),
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
    }
}

/**
 * The Pro sheet: aperture ladder, exposure and the shoot toggles.
 *
 * `expandVertically(clip = false)` keeps the sheet's contents from being sliced
 * while it slides open — the clipping artefact that usually shows up on
 * height-animated panels.
 */
@Composable
fun ProSheet(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .background(CameraColors.GlassStrong, RoundedCornerShape(CameraSpacing.panelRadius))
            .border(
                width = 0.5.dp,
                color = CameraColors.SeparatorStrong,
                shape = RoundedCornerShape(CameraSpacing.panelRadius),
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "PRO", style = CameraType.Section, color = CameraColors.LabelTertiary)
            Spacer(Modifier.weight(1f))
            Text(
                text = "Done",
                style = CameraType.Value,
                color = CameraColors.Yellow,
                modifier = Modifier
                    .clickable { actions.onProToggle() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        AnimatedVisibility(
            visible = state.mode == CaptureMode.Portrait,
            enter = fadeIn(tween(160)) + expandVertically(
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                expandFrom = Alignment.Top,
                clip = false,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = tween(160),
                shrinkTowards = Alignment.Top,
                clip = false,
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
                    value = state.fStopIndex.toFloat(),
                    valueRange = 0f..CameraController.FSTOPS.lastIndex.toFloat(),
                    onValueChange = { actions.onApertureChanged(it.roundToInt()) },
                    steps = CameraController.FSTOPS.lastIndex,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("1.4", style = CameraType.Caption, fontSize = 9.sp, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("2.5", style = CameraType.Caption, fontSize = 9.sp, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("4.5", style = CameraType.Caption, fontSize = 9.sp, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("8", style = CameraType.Caption, fontSize = 9.sp, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("16", style = CameraType.Caption, fontSize = 9.sp, color = CameraColors.LabelQuaternary)
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EXPOSURE", style = CameraType.Section, color = CameraColors.LabelTertiary)
            Spacer(Modifier.weight(1f))
            Text(state.evLabel, style = CameraType.Value, color = CameraColors.Label)
        }
        IosSlider(
            value = state.evSteps.toFloat(),
            valueRange = -12f..12f,
            onValueChange = { actions.onEvChanged(it.roundToInt()) },
            steps = 24,
        )

        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CameraColors.Separator),
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TogglePill("Grid", state.grid, { actions.onGridToggle() }, Modifier.weight(1f))
            TogglePill("RAW", state.raw, { actions.onRawToggle() }, Modifier.weight(1f))
            TogglePill(
                label = if (state.timerSeconds == 0) "Timer" else "${state.timerSeconds}s",
                active = state.timerSeconds > 0,
                onClick = { actions.onTimerCycle() },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = state.telemetry,
            style = CameraType.Telemetry,
            color = CameraColors.LabelTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
                value = state.fStopIndex.toFloat(),
                valueRange = 0f..CameraController.FSTOPS.lastIndex.toFloat(),
                onValueChange = { actions.onApertureChanged(it.roundToInt()) },
                steps = CameraController.FSTOPS.lastIndex,
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
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = CameraSpacing.gutter),
    ) {
        GlassIconButton(
            icon = when (state.flash) {
                FlashMode.Off -> NotiIcons.FlashOff
                FlashMode.Auto -> NotiIcons.FlashAuto
                FlashMode.On -> NotiIcons.FlashOn
            },
            contentDescription = "Flash",
            onClick = { actions.onFlashClick() },
            modifier = Modifier.align(Alignment.CenterStart),
        )

        StatusPill(
            text = state.status,
            modifier = Modifier.align(Alignment.Center),
        )

        GlassIconButton(
            icon = if (state.proSheetOpen) NotiIcons.Close else NotiIcons.Sliders,
            contentDescription = "Pro controls",
            onClick = { actions.onProToggle() },
            selected = state.proSheetOpen,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
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
        AnimatedVisibility(
            visible = state.proSheetOpen,
            enter = fadeIn(tween(150)) + expandVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                expandFrom = Alignment.Bottom,
                clip = false,
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = tween(170),
                shrinkTowards = Alignment.Bottom,
                clip = false,
            ),
        ) {
            ProSheet(
                state = state,
                actions = actions,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }

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
