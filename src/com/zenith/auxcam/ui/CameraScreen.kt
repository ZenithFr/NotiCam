package com.zenith.auxcam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.DeviceProfile
import com.zenith.auxcam.ui.components.BottomBar
import com.zenith.auxcam.ui.components.ProSheet
import com.zenith.auxcam.ui.components.QuickApertureRow
import com.zenith.auxcam.ui.components.TopBar
import com.zenith.auxcam.ui.components.Viewfinder
import com.zenith.auxcam.ui.components.ZoomChips
import com.zenith.auxcam.ui.components.ZoomDial
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraSpacing
import com.zenith.auxcam.ui.theme.CameraType
import kotlin.math.roundToInt

/**
 * Root of the capture UI.
 *
 * Layout contract — the chrome can never overlap the sensor image:
 *
 * ```
 * Column(safeDrawing insets)
 *  ├─ TopBar                       (flash · status · Pro)      fixed 56 dp
 *  ├─ Box(weight = 1)              ← only spare space lives here
 *  │    └─ Viewfinder, measured to exactly DeviceProfile.previewAspect
 *  ├─ Lens chips                                               fixed
 *  ├─ Quick aperture (Portrait only)                            animated
 *  └─ BottomBar (Pro sheet · modes · shutter)                   fixed
 * ```
 *
 * Because the viewfinder sits between fixed-height siblings and is measured to
 * the sensor aspect inside a `weight(1f)` slot, it is *mathematically* incapable
 * of overlapping the chrome — no manual offsets, no z-order tricks.
 */
@Composable
fun CameraScreen(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    var shutterCenter by remember { mutableStateOf(Offset.Zero) }
    var galleryCenter by remember { mutableStateOf(Offset.Zero) }

    // Bottom edge of the 4:3 frame, in root pixels. The Pro menu is capped to the
    // chrome band below this line, so it can never cover the framebuffer.
    var frameBottomPx by remember { mutableStateOf(0f) }
    var rootHeightPx by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CameraColors.Black)
            .onSizeChanged { rootHeightPx = it.height.toFloat() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            TopBar(state = state, actions = actions)

            if (!DeviceProfile.isTargetDevice()) {
                DeviceWarning()
            }

            // Everything above and below this slot has a fixed height, so the
            // viewfinder can only ever use the space that is left over.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                // Fit the sensor rectangle inside the slot without stretching it:
                // width-bound when the slot is proportionally tall enough, otherwise
                // height-bound. Expressed in dp so the surface gets exact pixels.
                val sensorAspect = DeviceProfile.previewAspect
                val widthBound = maxWidth / sensorAspect <= maxHeight
                val frameWidth = if (widthBound) maxWidth else maxHeight * sensorAspect
                val frameHeight = if (widthBound) maxWidth / sensorAspect else maxHeight

                Viewfinder(
                    gridVisible = state.grid,
                    capturePulse = state.capturePulse,
                    focusNonce = state.focusNonce,
                    enabled = true,
                    onSurfaceCreated = actions::onPreviewSurfaceCreated,
                    onSurfaceChanged = actions::onPreviewSurfaceChanged,
                    onSurfaceDestroyed = actions::onPreviewSurfaceDestroyed,
                    onTap = actions::onFocusAt,
                    onZoomGesture = actions::onZoomGesture,
                    modifier = Modifier
                        .size(frameWidth, frameHeight)
                        .onGloballyPositioned { coords ->
                            frameBottomPx = coords.positionInRoot().y + coords.size.height
                        },
                )
            }

            // Lens chips hug the bottom edge of the frame. One travelling disc
            // marks the selection, so a zoom change reads as a single movement.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                ZoomChips(
                    zoom = state.zoom,
                    onSelect = actions::onZoomSelected,
                    stops = DeviceProfile.ZOOM_STOPS,
                )
            }

            // The ƒ row keeps its space even when hidden: animating the column
            // height would resize the SurfaceView (and therefore the Vulkan
            // swapchain) on every frame of the transition.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(
                    visible = state.quickApertureVisible,
                    enter = fadeIn(tween(170)) + slideInVertically(
                        animationSpec = CameraMotion.slide<IntOffset>(),
                        initialOffsetY = { it / 3 },
                    ),
                    exit = fadeOut(tween(120)) + slideOutVertically(
                        animationSpec = tween<IntOffset>(140),
                        targetOffsetY = { it / 3 },
                    ),
                ) {
                    QuickApertureRow(state = state, actions = actions)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBottomScrim(),
            ) {
                BottomBar(
                    state = state,
                    actions = actions,
                    onShutterCenter = { shutterCenter = it },
                    onGalleryCenter = { galleryCenter = it },
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                )
            }
        }

        // ── Overlays: siblings of the column, so nothing can clip them ───────
        // The wheel rises and settles rather than popping: translate 14 dp up with
        // the scale, on the damped slide spring, and fade slightly ahead of both.
        AnimatedVisibility(
            visible = state.zoomDialVisible,
            enter = fadeIn(tween(120)) + scaleIn(
                initialScale = 0.92f,
                animationSpec = CameraMotion.slide<Float>(),
            ) + slideInVertically(
                animationSpec = CameraMotion.slide<IntOffset>(),
                initialOffsetY = { it / 26 },
            ),
            exit = fadeOut(tween(200)) + scaleOut(
                targetScale = 0.96f,
                animationSpec = tween(200),
            ),
            modifier = Modifier.align(Alignment.Center),
        ) {
            ZoomDial(
                zoom = state.zoom,
                onFraction = actions::onZoomFraction,
                onCommit = actions::onZoomCommit,
            )
        }

        ProMenuOverlay(
            open = state.proSheetOpen,
            bandHeightPx = (rootHeightPx - frameBottomPx).coerceAtLeast(0f),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ProSheet(state = state, actions = actions)
        }

        GalleryHandoff(
            pulse = state.galleryPulse,
            thumbnail = state.lastThumbnail,
            from = shutterCenter,
            to = galleryCenter,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

/** Subtle black ramp behind the toolbar, replacing the old solid slab. */
private fun Modifier.drawBottomScrim(): Modifier = this.background(
    Brush.verticalGradient(
        0f to Color.Transparent,
        0.28f to CameraColors.Black.copy(alpha = 0.55f),
        1f to CameraColors.Black.copy(alpha = 0.92f),
    ),
)

/** A single line under the top bar when running on non-target hardware. */
@Composable
private fun DeviceWarning() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CameraSpacing.gutter, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(6.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(CameraColors.Yellow),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Tuned for ${DeviceProfile.MARKETING_NAME} · running on ${DeviceProfile.describe()}",
            style = CameraType.Caption,
            color = CameraColors.LabelTertiary,
            maxLines = 1,
        )
    }
}

/**
 * The "photo flies into the gallery" hand-off, drawn at the root so it can travel
 * across the whole screen without being clipped by the toolbar or the frame.
 */
@Composable
private fun GalleryHandoff(
    pulse: Int,
    thumbnail: ImageBitmap?,
    from: Offset,
    to: Offset,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(1f) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(pulse) {
        if (pulse > 0 && thumbnail != null && from != Offset.Zero && to != Offset.Zero) {
            visible = true
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
            )
            visible = false
        }
    }

    if (!visible || thumbnail == null) return

    val size = 96.dp
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val liftPx = with(density) { 26.dp.toPx() }
    val t = progress.value
    // Slight arc so the tile lifts before it lands in the gallery.
    val x = from.x + (to.x - from.x) * t
    val y = from.y + (to.y - from.y) * t - liftPx * kotlin.math.sin(t * Math.PI).toFloat()
    val scale = 0.72f + 0.28f * t

    Image(
        bitmap = thumbnail,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .offset { IntOffset((x - sizePx / 2f).roundToInt(), (y - sizePx / 2f).roundToInt()) }
            .width(size)
            .height(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = (1f - (t - 0.75f).coerceAtLeast(0f) * 4f).coerceIn(0f, 1f)
                clip = false
            }
            .clip(RoundedCornerShape(12.dp)),
    )
}

/**
 * The Pro menu, sliding up out of the bottom of the screen.
 *
 * It is a root-level overlay rather than part of the column on purpose:
 *
 *  • **No layout change** while it opens, so the `SurfaceView` (and therefore the
 *    Vulkan swapchain) is never resized mid-animation;
 *  • **No overlap with the 4:3 frame** — the panel's max height is the chrome band
 *    below the viewfinder, so it slides over the controls, never over the image;
 *  • The motion is a translate + fade + 3 % scale on one damped spring, with the
 *    content scrolling inside if the band is short (landscape, big font scale).
 */
@Composable
private fun ProMenuOverlay(
    open: Boolean,
    bandHeightPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Height available between the bottom of the 4:3 frame and the bottom of the
    // window — the menu is clamped to it, so it can slide over the controls but
    // never over the image. Guarded against the pre-measure pass (0 px).
    val density = LocalDensity.current
    val cap = with(density) { bandHeightPx.coerceAtLeast(0f).toDp() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CameraSpacing.gutterWide)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(180)) + scaleIn(
                initialScale = 0.97f,
                transformOrigin = TransformOrigin(0.5f, 1f),
                animationSpec = CameraMotion.slide<Float>(),
            ) + slideInVertically(
                animationSpec = CameraMotion.slide<IntOffset>(),
                initialOffsetY = { it },
            ),
            exit = fadeOut(tween(140)) + scaleOut(
                targetScale = 0.97f,
                transformOrigin = TransformOrigin(0.5f, 1f),
                animationSpec = tween(160),
            ) + slideOutVertically(
                animationSpec = tween<IntOffset>(170),
                targetOffsetY = { it },
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (cap > 12.dp) cap - 12.dp else 0.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        }
    }
}
