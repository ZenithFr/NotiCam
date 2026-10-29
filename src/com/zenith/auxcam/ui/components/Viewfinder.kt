package com.zenith.auxcam.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zenith.auxcam.DeviceProfile
import com.zenith.auxcam.ui.feedback.rememberCameraHaptics
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import kotlinx.coroutines.delay

/**
 * The live bokeh viewfinder.
 *
 * Geometry contract
 * ─────────────────
 * The engine copies its composited frame 1:1 into the swapchain, whose extent is
 * the SurfaceView's pixel size. [com.zenith.auxcam.ui.CameraScreen] sizes this box
 * to **exactly** [DeviceProfile.previewAspect] inside the space the chrome leaves,
 * so the shader never has to stretch the sensor frame to fill a mismatched area.
 * `composite.comp` additionally letterboxes as a belt-and-braces guarantee.
 *
 * Everything the UI draws on top (grid, focus reticle, capture bloom) is Compose
 * content inside the same box: the SurfaceView surface sits *behind* the window,
 * so these overlays are never hidden by it.
 */
@Composable
fun Viewfinder(
    gridVisible: Boolean,
    zoom: Float,
    capturePulse: Int,
    focusNonce: Int,
    enabled: Boolean,
    onSurfaceCreated: (SurfaceHolder) -> Unit,
    onSurfaceChanged: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: (SurfaceHolder) -> Unit,
    onTap: (xFraction: Float, yFraction: Float) -> Unit,
    onZoomGesture: (scaleFactor: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var viewSize by remember { mutableStateOf(Size.Zero) }
    val haptics = rememberCameraHaptics()
    // Pinch detents are measured against the *live* engine zoom, not the gesture's
    // own accumulation, so they line up with the 0.6x/1x/2x stops exactly.
    val currentZoom by rememberUpdatedState(zoom)

    val reticleScale = remember { Animatable(0.72f) }
    val reticleAlpha = remember { Animatable(0f) }

    LaunchedEffect(focusNonce) {
        if (focusNonce > 0 && focusPoint != null) {
            haptics.focus()
            reticleAlpha.snapTo(0f)
            reticleScale.snapTo(0.72f)
            reticleAlpha.animateTo(1f, tween(durationMillis = 110))
            reticleScale.animateTo(targetValue = 1f, animationSpec = CameraMotion.release())
            delay(1200)
            reticleAlpha.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        }
    }

    val bloomAlpha = remember { Animatable(0f) }
    LaunchedEffect(capturePulse) {
        if (capturePulse > 0) {
            bloomAlpha.snapTo(0f)
            bloomAlpha.animateTo(0.85f, tween(durationMillis = 55))
            bloomAlpha.animateTo(0f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
        }
    }

    // The caller (CameraScreen) sizes this box to exactly `previewAspect`; the
    // background guarantees no white flash before the first frame arrives.
    Box(
        modifier = modifier.background(CameraColors.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = onSurfaceCreated(holder)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                            onSurfaceChanged(holder)

                        override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceDestroyed(holder)
                    })
                }
            },
        )

        // Rule-of-thirds grid.
        if (gridVisible) {
            Canvas(Modifier.fillMaxSize()) {
                val hairline = 0.5.dp.toPx()
                val color = Color.White.copy(alpha = 0.22f)
                drawLine(color, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), hairline)
                drawLine(color, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), hairline)
                drawLine(color, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), hairline)
                drawLine(color, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), hairline)
            }
        }

        // Capture bloom, confined to the scene. The alpha is read inside the
        // graphics layer so the animation never triggers recomposition.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = bloomAlpha.value }
                .background(Color.White),
        )

        // AE/AF reticle.
        focusPoint?.let { point ->
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = reticleAlpha.value
                        scaleX = reticleScale.value
                        scaleY = reticleScale.value
                        transformOrigin = TransformOrigin(
                            pivotFractionX = if (viewSize.width > 0f) point.x / viewSize.width else 0.5f,
                            pivotFractionY = if (viewSize.height > 0f) point.y / viewSize.height else 0.5f,
                        )
                        clip = false
                    },
            ) {
                val half = 36.dp.toPx()
                val topLeft = Offset(point.x - half, point.y - half)
                drawRoundRect(
                    color = CameraColors.Yellow,
                    topLeft = topLeft,
                    size = Size(half * 2f, half * 2f),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.6.dp.toPx()),
                )
                drawCircle(color = CameraColors.Yellow, radius = 2.2.dp.toPx(), center = point)
            }
        }

        // Gestures: tap to focus, pinch to zoom. Two independent detectors so a
        // pinch never registers as a focus tap.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        viewSize = Size(size.width.toFloat(), size.height.toFloat())
                        focusPoint = offset
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        onTap((offset.x / w).coerceIn(0f, 1f), (offset.y / h).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var lastStop = -1
                    detectTransformGestures(
                        onGestureEnd = {
                            lastStop = -1
                            haptics.gestureEnd()
                        },
                    ) { _, _, scale, _ ->
                        if (scale == 1f) return@detectTransformGestures
                        onZoomGesture(scale)
                        // Tick once per lens stop crossed, not per pointer event.
                        val stop = nearestStopIndex(currentZoom)
                        if (stop != lastStop) {
                            lastStop = stop
                            haptics.tick()
                        }
                    }
                },
        )
    }
}

