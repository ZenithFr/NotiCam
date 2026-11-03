package com.zenith.auxcam.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraController
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.CaptureMode
import com.zenith.auxcam.FlashMode
import com.zenith.auxcam.Resolution
import com.zenith.auxcam.ui.effects.cameraGlass
import com.zenith.auxcam.ui.feedback.rememberCameraHaptics
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraSpacing
import com.zenith.auxcam.ui.theme.CameraType
import kotlin.math.roundToInt

/**
 * Borderless glass icon button.
 *
 * A translucent disc with soft interior light — no decorative rim or native
 * backdrop blur, so on a bright scene you read the glyph against the picture
 * rather than against a card. The 34 dp circle lives inside a 48 dp touch target
 * and only the circle is scaled on press (`clip = false`), so the glyph is never
 * cropped by the neighbouring layout while the animation runs.
 */
@Composable
fun MatteIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = CameraColors.Label,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberCameraHaptics()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        // Down: crisp. Up: a touch of overshoot. This asymmetry is the whole trick.
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "matte-press",
    )
    val surface by animateColorAsState(
        targetValue = if (selected) CameraColors.Accent else CameraColors.ControlSurface,
        animationSpec = CameraMotion.select(),
        label = "matte-surface",
    )
    val availability by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = CameraMotion.fade(180), label = "matte-availability",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) CameraColors.Label else tint,
        animationSpec = tween(180),
        label = "matte-fg",
    )

    Box(
        modifier = modifier
            .size(CameraSpacing.touch)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.tap()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(CameraSpacing.iconButton)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    clip = false
                }
                .cameraGlass(CircleShape, baseColor = surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = foreground.copy(alpha = foreground.alpha * availability),
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
            .background(CameraColors.Matte, RoundedCornerShape(15.dp))
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
        targetValue = if (selected) Color.White else CameraColors.Matte,
        animationSpec = tween(200),
        label = "chip-bg",
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) CameraColors.OnLight else CameraColors.Label,
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
            .cameraGlass(CircleShape, background)
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

/** Direct under the finger; spring-settled on taps, resets and release. */
@Composable
fun IosSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    detents: List<Float> = emptyList(),
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var lastTick by remember { mutableIntStateOf(-1) }
    val onChange by rememberUpdatedState(onValueChange)
    val haptics = rememberCameraHaptics()
    val insetPx = with(LocalDensity.current) { 10.dp.toPx() }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val displayedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (dragging) snap() else CameraMotion.fluid(),
        label = "slider-position",
    )
    val knobScale by animateFloatAsState(
        targetValue = if (dragging || pressed) 1.2f else 1f,
        animationSpec = CameraMotion.release(),
        label = "slider-touch",
    )
    fun emit(x: Float) {
        val rawPosition = ((x - insetPx) / (widthPx - 2f * insetPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val position = if (detents.isEmpty()) rawPosition else detents.minBy { kotlin.math.abs(it - rawPosition) }
        val detent = if (detents.isEmpty()) (position * 12f).roundToInt() else detents.indexOf(position)
        if (dragging && detent != lastTick) { lastTick = detent; haptics.tick() }
        onChange(valueRange.start + position * span)
    }
    Box(
        modifier = modifier.fillMaxWidth().height(44.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange)
                setProgress { requested -> onChange(requested.coerceIn(valueRange)); true }
            }
            .pointerInput(valueRange, detents) {
                detectTapGestures(
                    onPress = { pressed = true; try { tryAwaitRelease() } finally { pressed = false } },
                    onTap = { offset -> emit(offset.x) },
                )
            }
            .pointerInput(valueRange, detents) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; lastTick = -1; emit(it.x) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ -> change.consume(); emit(change.position.x) }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val trackHeight = 3.dp.toPx()
            val radius = CornerRadius(trackHeight / 2f)
            val knobRadius = 10.dp.toPx()
            val travel = (size.width - knobRadius * 2f).coerceAtLeast(1f)
            val knobX = knobRadius + travel * displayedFraction
            drawRoundRect(CameraColors.SliderTrack, Offset(knobRadius, cy - trackHeight / 2f),
                Size(travel, trackHeight), radius)
            drawRoundRect(CameraColors.SliderFill, Offset(knobRadius, cy - trackHeight / 2f),
                Size(travel * displayedFraction, trackHeight), radius)
            for (stop in detents) drawLine(CameraColors.LabelSecondary.copy(alpha = .45f),
                Offset(knobRadius + travel * stop, cy - 5.dp.toPx()), Offset(knobRadius + travel * stop, cy + 5.dp.toPx()), 1.dp.toPx())
            drawCircle(CameraColors.Label, knobRadius * knobScale, Offset(knobX, cy))
        }
    }
}

/** White-when-active capsule used inside the camera-controls sheet. */
@Composable
fun TogglePill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "toggle-press",
    )
    val background by animateColorAsState(
        targetValue = if (active) Color.White else CameraColors.Matte,
        animationSpec = tween(180),
        label = "toggle-bg",
    )
    // White selection needs dark text; the old white-on-white was unreadable.
    val foreground by animateColorAsState(
        targetValue = if (active) CameraColors.OnLight else CameraColors.Label,
        animationSpec = tween(180),
        label = "toggle-fg",
    )
    val haptics = rememberCameraHaptics()
    Box(
        modifier = modifier
            .heightIn(min = CameraSpacing.capsuleHeight)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.38f
                scaleX = scale
                scaleY = scale
                clip = false
            }
            .semantics { selected = active }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.select()
                    onClick()
                },
            )
            .padding(vertical = 4.dp)
            .cameraGlass(RoundedCornerShape(20.dp), background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = CameraType.Value, color = foreground, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
    val shape = RoundedCornerShape(18.dp)
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
            .cameraGlass(shape)
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
        Crossfade(targetState = thumbnail, animationSpec = tween(260), label = "gallery-frame") { frame ->
            if (frame != null) {
                androidx.compose.foundation.Image(
                    bitmap = frame,
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
        targetValue = if (selected) Color.White else CameraColors.Matte,
        animationSpec = tween(190),
        label = "segment-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) CameraColors.OnLight else CameraColors.LabelSecondary,
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
            .cameraGlass(RoundedCornerShape(16.dp), bg)
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

/** Inverse of [CameraUiState.apertureFraction]: 0..1 → f-number on a log scale. */
internal fun fStopForFraction(fraction: Float): Float {
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

/** Reset chip with tactile press animation and white accent when active. */
@Composable
fun ResetChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberCameraHaptics()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = if (pressed) CameraMotion.press() else CameraMotion.release(),
        label = "reset-press",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .cameraGlass(RoundedCornerShape(10.dp), CameraColors.Accent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.tick()
                    onClick()
                },
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "RESET",
            style = CameraType.Caption,
            color = CameraColors.Accent,
        )
    }
}

/** Manual focus bar with a reset chip, slider, and distance label. */
@Composable
fun QuickFocusRow(
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
            imageVector = NotiIcons.Focus,
            contentDescription = "Manual Focus",
            tint = if (state.manualFocus) CameraColors.Accent else CameraColors.LabelSecondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            IosSlider(
                value = state.focusFraction,
                valueRange = 0f..1f,
                onValueChange = actions::onManualFocusChanged,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = state.focusLabel,
            style = CameraType.Value,
            color = if (state.manualFocus) CameraColors.Accent else CameraColors.Label,
            modifier = Modifier.width(54.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        if (state.manualFocus || state.aeAfLocked) {
            Spacer(Modifier.width(6.dp))
            ResetChip(onClick = actions::onFocusReset)
        }
    }
}

/** Compact header: RAW at top-left, profile/flash/settings on the right. */
@Composable
fun TopBar(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RawCaptureButton(state, actions)
        Spacer(Modifier.weight(1f))
        // Independent resolution/rate controls, not a combined misleading "4K+". The entire >=48dp surface is interactive.
        Row(Modifier.width(if (state.mode == CaptureMode.Video) 128.dp else 80.dp).heightIn(min = CameraSpacing.touch), verticalAlignment = Alignment.CenterVertically) {
            val photo = state.mode == CaptureMode.Photo
            val label = if (photo) {
                if (state.raw) "${if (state.resolution == Resolution.High) "50" else "12"} RAW" else if (state.resolution == Resolution.High) "50 MP" else state.photoSizeLabel
            } else when {
                state.videoQuality == com.zenith.auxcam.VideoPolicy.UHD -> "4K"
                else -> "1080"
            }
            Column(Modifier.weight(1f).heightIn(min = CameraSpacing.touch)
                .clip(RoundedCornerShape(CameraSpacing.controlRadius))
                .background(Color.White)
                .clickable(enabled = state.sessionReady && !state.captureBusy && !state.videoRecording && !state.videoStarting,
                    role = Role.Button, onClick = actions::onResolutionToggle)
                .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(label, style = CameraType.Value, color = Color.Black,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!photo) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.heightIn(min = CameraSpacing.touch)
                    .clip(RoundedCornerShape(CameraSpacing.controlRadius))
                    .background(Color.White)
                    .clickable(
                        enabled = state.sessionReady && !state.captureBusy && !state.videoRecording && !state.videoStarting,
                        role = Role.Button,
                        onClick = {
                            val rates = if (state.videoQuality == com.zenith.auxcam.VideoPolicy.HD) listOf(24, 30, 60) else listOf(24, 30)
                            actions.onVideoRateSelected(rates[(rates.indexOf(state.videoTargetFps) + 1) % rates.size])
                        }
                    ).padding(horizontal = 8.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text("${state.videoTargetFps} fps", style = CameraType.Telemetry,
                        color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(CameraSpacing.touch), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (state.settingsOpen) CameraColors.Accent else Color.White)
                .clickable(enabled = !state.videoRecording && !state.videoStarting && !state.captureBusy && !state.previewTransitioning,
                    role = Role.Button, onClick = actions::onSettingsToggle),
                contentAlignment = Alignment.Center) {
                Icon(if (state.settingsOpen) NotiIcons.Close else NotiIcons.Settings,
                    contentDescription = "Open settings",
                    tint = if (state.settingsOpen) Color.White else Color.Black,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Labeled Photo / Video switch with one travelling white pill.
 *
 * Replaces the old icon-only toggle: the mode is now readable at a glance and
 * each option is a named, >=48 dp target. Selection semantics are exposed for
 * accessibility; the pill slides rather than blinking two separate fills.
 */
@Composable
fun ModeSwitch(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    val enabled = state.sessionReady && !state.videoRecording && !state.videoStarting &&
        !state.captureBusy && !state.previewTransitioning
    val haptics = rememberCameraHaptics()
    val photoSelected = state.mode == CaptureMode.Photo
    val pillX by animateDpAsState(
        targetValue = if (photoSelected) 96.dp else 0.dp,
        animationSpec = CameraMotion.select(), label = "mode-pill",
    )
    Box(
        modifier = modifier
            .cameraGlass(RoundedCornerShape(24.dp))
            .padding(4.dp)
            .semantics { contentDescription = "Camera mode" },
    ) {
        Box(
            modifier = Modifier.offset(x = pillX).width(96.dp).height(40.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color.White),
        )
        Row {
            listOf(CaptureMode.Video, CaptureMode.Photo).forEach { mode ->
                val selected = state.mode == mode
                val fg by animateColorAsState(
                    targetValue = if (selected) CameraColors.OnLight else CameraColors.LabelSecondary,
                    animationSpec = tween(180), label = "mode-fg",
                )
                Box(
                    modifier = Modifier.width(96.dp).heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .semantics { this.selected = selected }
                        .clickable(enabled = enabled, role = Role.Button, onClick = {
                            haptics.select()
                            actions.onModeSelected(mode)
                        })
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(mode.label, style = CameraType.Mode, color = fg, maxLines = 1)
                }
            }
        }
    }
}

/** Capture modes, gallery and the single-flight shutter. */
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
        // Settings use a full-screen overlay and never resize the native preview.
        ModeSwitch(state, actions, Modifier.padding(bottom = 10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 8.dp),
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
                videoMode = state.mode == CaptureMode.Video,
                enabled = state.canUseShutter,
                recording = state.videoRecording,
                busy = state.captureBusy,
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

            // Equal-footprint spacer keeps the shutter centered opposite the gallery.
            Spacer(Modifier.size(CameraSpacing.gallery))
        }
    }
}
