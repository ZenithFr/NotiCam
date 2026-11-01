package com.zenith.auxcam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.CaptureMode
import com.zenith.auxcam.DeviceProfile
import com.zenith.auxcam.Resolution
import com.zenith.auxcam.ui.components.LensBlend
import com.zenith.auxcam.ui.components.BottomBar
import com.zenith.auxcam.ui.components.CameraSettings
import com.zenith.auxcam.ui.components.MatteIconButton
import com.zenith.auxcam.ui.components.QuickApertureRow
import com.zenith.auxcam.ui.components.TopBar
import com.zenith.auxcam.ui.components.Viewfinder
import com.zenith.auxcam.ui.components.ZoomChips
import com.zenith.auxcam.ui.effects.SceneAtmosphere
import com.zenith.auxcam.ui.effects.cameraGlass
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraType
import kotlin.math.roundToInt

/**
 * Scene-first camera: borderless glass controls, a two-mode dock and an explicit
 * portrait-effect button. No backdrop blur over a SurfaceView and no resizing
 * of the preview when controls open or the effect is toggled.
 */
@Composable
fun CameraScreen(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    BackHandler(enabled = state.settingsOpen) { actions.onSettingsToggle() }
    var shutterCenter by remember { mutableStateOf(Offset.Zero) }
    var galleryCenter by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().background(CameraColors.Black)) {
        SceneAtmosphere(state.lastThumbnail, Modifier.matchParentSize())
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(state = state, actions = actions)
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val aspect = DeviceProfile.previewAspect
                val widthBound = maxWidth / aspect <= maxHeight
                val frameWidth = if (widthBound) maxWidth else maxHeight * aspect
                val frameHeight = if (widthBound) maxWidth / aspect else maxHeight
                Box(Modifier.size(frameWidth, frameHeight).clip(RoundedCornerShape(24.dp))) {
                    LensBlend(state, actions, Modifier.fillMaxSize()) {
                        Viewfinder(
                            gridVisible = state.grid,
                            zoom = state.zoom,
                            capturePulse = state.capturePulse,
                            focusNonce = state.focusNonce,
                            enabled = state.sessionReady && !state.previewTransitioning && !state.captureBusy && !state.videoRecording,
                            zoomEnabled = (state.sessionReady || state.previewTransitioning) && !state.captureBusy && !state.videoRecording,
                            onSurfaceCreated = actions::onPreviewSurfaceCreated,
                            onSurfaceChanged = actions::onPreviewSurfaceChanged,
                            onSurfaceDestroyed = actions::onPreviewSurfaceDestroyed,
                            onTap = actions::onFocusAt,
                            onZoomGesture = actions::onZoomGesture,
                            locked = state.aeAfLocked,
                            onLock = actions::onFocusLock,
                            onUnlock = actions::onFocusUnlock,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // A modest gradient gives controls contrast without grading the scene.
                    Box(Modifier.fillMaxWidth().height(100.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x70000000)))))
                    ModeBadge(
                        label = when {
                            state.videoRecording -> String.format(java.util.Locale.US, "● %02d:%02d", state.videoElapsedSeconds / 60, state.videoElapsedSeconds % 60)
                            state.mode == CaptureMode.Video -> if (state.videoWidth > 0) "VIDEO  ·  ${state.videoWidth} × ${state.videoHeight}" else "VIDEO  ·  NATIVE FRAME"
                            state.bokehActive -> "PORTRAIT  ${state.fStopLabel}"
                            state.resolution == Resolution.High -> "50 MP  ·  1×"
                            else -> null
                        },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = state.mode == CaptureMode.Photo,
                        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.86f, animationSpec = CameraMotion.slide()),
                        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.86f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp),
                    ) {
                        MatteIconButton(
                            icon = NotiIcons.Portrait,
                            contentDescription = if (state.bokehActive) "Turn portrait effect off" else "Turn portrait effect on",
                            onClick = actions::onBokehToggle,
                            selected = state.bokehActive,
                            enabled = state.sessionReady && !state.captureBusy && !state.videoRecording && !state.previewTransitioning,
                        )
                    }
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                            .cameraGlass(RoundedCornerShape(24.dp)).padding(horizontal = 2.dp)
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragStart = { actions.onZoomDragStart() },
                                    onDragEnd = { actions.onZoomDragEnd() },
                                    onDragCancel = { actions.onZoomDragEnd() },
                                ) { change, amount -> change.consume(); actions.onZoomDrag(amount) }
                            },
                    ) {
                        ZoomChips(zoom = state.zoom, onSelect = actions::onZoomSelected,
                            stops = DeviceProfile.ZOOM_STOPS, enabled = state.sessionReady && !state.captureBusy && !state.videoRecording)
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = state.zoomActive,
                        enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.88f, animationSpec = CameraMotion.slide()),
                        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.94f),
                        modifier = Modifier.align(Alignment.Center),
                    ) { ZoomReadout(zoom = state.zoom) }
                }
            }
            // Reserved height keeps the Vulkan surface stable as portrait controls appear.
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                Crossfade(
                    targetState = state.bokehActive && state.sessionReady && !state.captureBusy,
                    animationSpec = tween(220), label = "aperture-strip",
                ) { apertureVisible ->
                    if (apertureVisible) QuickApertureRow(state = state, actions = actions)
                    else {
                        val message = when {
                            state.videoRecording -> if (state.videoHasAudio) "Recording • audio on" else "Recording • microphone off"
                            state.mode == CaptureMode.Video && state.captureBusy -> state.status
                            state.mode == CaptureMode.Video -> if (state.status == "Ready")
                                "${state.videoHeight}p · 30 fps · ${if (state.videoHasAudio) "Audio on" else "Mic permission requested on Record"}"
                                else state.status
                            !DeviceProfile.isTargetDevice() -> "Designed for CMF Phone 2 Pro"
                            state.status == "Ready" -> "${state.photoSizeLabel}  ·  4:3  ·  JPEG"
                            else -> state.status
                        }
                        Crossfade(targetState = message, animationSpec = tween(180), label = "camera-status") { text ->
                            Text(text = text, style = CameraType.Caption,
                                color = if (state.captureBusy) CameraColors.Accent else CameraColors.LabelSecondary,
                                modifier = Modifier.padding(horizontal = 24.dp), maxLines = 2)
                        }
                    }
                }
            }
            BottomBar(state = state, actions = actions,
                onShutterCenter = { shutterCenter = it }, onGalleryCenter = { galleryCenter = it })
            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(CameraColors.LabelQuaternary))
                Spacer(Modifier.width(7.dp))
                Text("NOTICAM", style = CameraType.Section, color = CameraColors.LabelQuaternary)
                Spacer(Modifier.weight(1f))
            }
        }
        GalleryFlight(pulse = state.galleryPulse, thumbnail = state.lastThumbnail,
            from = shutterCenter, to = galleryCenter)
    }
    if (state.settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = actions::onSettingsToggle,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF191A1D),
            contentColor = Color.White,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        ) {
            CameraSettings(state = state, actions = actions,
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()).padding(bottom = 24.dp))
        }
    }
    if (state.galleryOpen) GalleryScreen(onClose = actions::onGalleryClose)
}

/** Preserve the last valid label while fading out, rather than flashing a false 50 MP badge. */
@Composable
private fun ModeBadge(label: String?, modifier: Modifier = Modifier) {
    var lastLabel by remember { mutableStateOf(label.orEmpty()) }
    SideEffect { if (label != null) lastLabel = label }
    AnimatedVisibility(
        visible = label != null,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = CameraMotion.slide()),
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.94f),
        modifier = modifier,
    ) {
        Text(text = label ?: lastLabel, style = CameraType.Caption, color = CameraColors.Accent,
            modifier = Modifier.cameraGlass(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** Presentation-only easing: never feeds interpolated commands back to Camera2. */
@Composable
private fun ZoomReadout(zoom: Float) {
    val displayedZoom by animateFloatAsState(zoom, CameraMotion.fluid(), label = "zoom-readout")
    Text(String.format(java.util.Locale.US, "%.1f×", displayedZoom),
        style = CameraType.Dial, color = Color.White,
        modifier = Modifier.cameraGlass(RoundedCornerShape(28.dp)).padding(horizontal = 22.dp, vertical = 12.dp))
}

/** A successful save alone sends a thumbnail into the gallery. Failed captures never fly. */
@Composable
private fun GalleryFlight(pulse: Int, thumbnail: ImageBitmap?, from: Offset, to: Offset) {
    val progress = remember { Animatable(1f) }
    var flyingImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val sizePx = with(LocalDensity.current) { 72.dp.toPx() }
    val liftPx = with(LocalDensity.current) { 32.dp.toPx() }
    LaunchedEffect(pulse) {
        if (pulse > 0 && thumbnail != null && from != Offset.Zero && to != Offset.Zero) {
            flyingImage = thumbnail
            try {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
            } finally { flyingImage = null }
        }
    }
    flyingImage?.let { image ->
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp)
                .offset {
                    val t = progress.value
                    IntOffset((from.x + (to.x - from.x) * t - sizePx / 2f).roundToInt(),
                        (from.y + (to.y - from.y) * t - liftPx * kotlin.math.sin(t * Math.PI).toFloat() - sizePx / 2f).roundToInt())
                }
                .graphicsLayer {
                    val t = progress.value
                    scaleX = 1f - 0.3f * t
                    scaleY = scaleX
                    alpha = (1f - (t - 0.8f).coerceAtLeast(0f) * 5f).coerceIn(0f, 1f)
                    rotationZ = -4f * kotlin.math.sin(t * Math.PI).toFloat()
                }.clip(RoundedCornerShape(12.dp)))
    }
}
