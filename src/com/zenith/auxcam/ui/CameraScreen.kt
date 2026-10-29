package com.zenith.auxcam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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

    // Top edge of the 0.6x/1x/2x chip row, in root pixels. The zoom wheel is
    // anchored here so its arc grows out of the chips.
    var chipsTopPx by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CameraColors.Black),
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
                    zoom = state.zoom,
                    capturePulse = state.capturePulse,
                    focusNonce = state.focusNonce,
                    enabled = true,
                    onSurfaceCreated = actions::onPreviewSurfaceCreated,
                    onSurfaceChanged = actions::onPreviewSurfaceChanged,
                    onSurfaceDestroyed = actions::onPreviewSurfaceDestroyed,
                    onTap = actions::onFocusAt,
                    onZoomGesture = actions::onZoomGesture,
                    modifier = Modifier.size(frameWidth, frameHeight),
                )
            }

            // Lens chips hug the bottom edge of the frame. One travelling disc
            // marks the selection, so a zoom change reads as a single movement.
            // The chips and the wheel are two states of one control: while the
            // wheel is up the chips collapse into the arc's anchor line.
            val chipsAlpha by animateFloatAsState(
                targetValue = if (state.zoomActive) 0f else 1f,
                animationSpec = CameraMotion.fade(if (state.zoomActive) 140 else 200),
                label = "chips-alpha",
            )
            val chipsMorph by animateFloatAsState(
                targetValue = if (state.zoomActive) 1f else 0f,
                animationSpec = CameraMotion.select(),
                label = "chips-morph",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .graphicsLayer {
                        alpha = chipsAlpha
                        val scale = 1f - 0.16f * chipsMorph
                        scaleX = scale
                        scaleY = scale
                        translationY = 6.dp.toPx() * chipsMorph
                        clip = false
                    }
                    .onGloballyPositioned { coords ->
                        chipsTopPx = coords.positionInRoot().y
                    },
                contentAlignment = Alignment.Center,
            ) {
                ZoomChips(
                    zoom = state.zoom,
                    onSelect = actions::onZoomSelected,
                    stops = DeviceProfile.ZOOM_STOPS,
                    enabled = !state.zoomActive,
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
        // The wheel: pinned so its arc lands on the chip row, and animated out of
        // that line (scale from the top edge) so the two controls read as one
        // object morphing rather than two widgets cross-fading.
        ZoomWheel(
            zoom = state.zoom,
            visible = state.zoomActive,
            anchorTopPx = chipsTopPx,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Settings sheet: hangs from the top edge, slides down over the frame.
        ProMenuOverlay(
            open = state.proSheetOpen,
            onClose = { actions.onProToggle() },
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
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Tap-outside-to-dismiss: there is no Done button any more, so the sheet
        // needs a second way out besides the top-bar Pro button.
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Stops under the top bar: flash / RAW / Pro stay legible and
                    // the Pro button keeps working as the close affordance.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = TOP_BAR_HEIGHT)
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }

        // The panel hangs just below the top bar, so the flash / RAW / Pro controls
        // stay visible and the Pro button remains the close affordance.
        AnimatedVisibility(
            visible = open,
            // Slides DOWN from behind the top bar: the panel unfolds out of the
            // header rather than fading in place.
            enter = fadeIn(tween(160)) + scaleIn(
                initialScale = 0.96f,
                transformOrigin = TransformOrigin(0.5f, 0f),
                animationSpec = CameraMotion.slide<Float>(),
            ) + slideInVertically(
                animationSpec = CameraMotion.slide<IntOffset>(),
                initialOffsetY = { -it },
            ),
            exit = fadeOut(tween(130)) + scaleOut(
                targetScale = 0.96f,
                transformOrigin = TransformOrigin(0.5f, 0f),
                animationSpec = tween(150),
            ) + slideOutVertically(
                animationSpec = tween<IntOffset>(160),
                targetOffsetY = { -it },
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = TOP_BAR_HEIGHT, start = CameraSpacing.gutterWide, end = CameraSpacing.gutterWide),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        }
    }
}

/** Must match `TopBar`'s height so the sheet tucks directly under it. */
private val TOP_BAR_HEIGHT = 56.dp

/**
 * The zoom wheel, anchored to the chip row and morphing out of it.
 *
 * `anchorTopPx` is the root-space top of the chips. The wheel is offset so its
 * **arc** — not its bounding box — sits over the chips, and the entrance scales
 * from the top edge so the arc appears to unfurl out of the chip line while the
 * chips collapse into it (see the `chipsAlpha`/`chipsMorph` animation above).
 *
 * The wheel takes no input: zoom is pinch-only, so this never steals a touch from
 * the shutter or the chips beneath it.
 */
@Composable
private fun ZoomWheel(
    zoom: Float,
    visible: Boolean,
    anchorTopPx: Float,
    modifier: Modifier = Modifier,
) {
    // The arc's ends sit ~24 % of the diameter below the wheel's top edge.
    val arcOverlapPx = with(LocalDensity.current) { 52.dp.toPx() }
    val offsetY = (anchorTopPx - arcOverlapPx).roundToInt()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)) + scaleIn(
            initialScale = 0.55f,
            transformOrigin = TransformOrigin(0.5f, 0f),
            animationSpec = CameraMotion.slide<Float>(),
        ),
        exit = fadeOut(tween(180)) + scaleOut(
            targetScale = 0.7f,
            transformOrigin = TransformOrigin(0.5f, 0f),
            animationSpec = tween(200),
        ),
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offsetY) },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            ZoomDial(zoom = zoom)
        }
    }
}
