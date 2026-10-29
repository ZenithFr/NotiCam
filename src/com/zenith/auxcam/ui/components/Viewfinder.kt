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
import androidx.compose.ui.graphics.StrokeCap
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
    locked: Boolean,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
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
    // 0 = plain AE/AF square, 1 = locked badge with a padlock and label.
    val lockMorph = remember { Animatable(0f) }

    LaunchedEffect(focusNonce) {
        if (focusNonce > 0 && focusPoint != null) {
            haptics.focus()
            reticleAlpha.snapTo(0f)
            reticleScale.snapTo(0.72f)
            reticleAlpha.animateTo(1f, tween(durationMillis = 110))
            reticleScale.animateTo(targetValue = 1f, animationSpec = CameraMotion.release())
            delay(1200)
            // A locked reticle stays put; only an unlocked one fades away.
            if (!locked) {
                reticleAlpha.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
            }
        }
    }

    LaunchedEffect(locked) {
        if (locked) {
            haptics.focusLock()
            reticleAlpha.animateTo(1f, tween(120))
            reticleScale.animateTo(0.82f, CameraMotion.release())
            lockMorph.animateTo(1f, CameraMotion.select())
        } else {
            if (lockMorph.value > 0.01f) haptics.focusUnlock()
            lockMorph.animateTo(0f, tween(180))
            reticleScale.animateTo(1f, CameraMotion.release())
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
                val m = lockMorph.value

                // The square contracts a little and its corners tighten when locked.
                val box = half * (1f - 0.18f * m)
                drawRoundRect(
                    color = CameraColors.Yellow,
                    topLeft = Offset(point.x - box, point.y - box),
                    size = Size(box * 2f, box * 2f),
                    cornerRadius = CornerRadius(4.dp.toPx() + 4.dp.toPx() * m),
                    style = Stroke(width = 1.6.dp.toPx() + 0.5.dp.toPx() * m),
                )

                if (m < 0.5f) {
                    // Unlocked: the classic centre dot.
                    drawCircle(
                        color = CameraColors.Yellow.copy(alpha = 1f - m * 2f),
                        radius = 2.2.dp.toPx(),
                        center = point,
                    )
                } else {
                    // Locked: a padlock glyph inside the square.
                    val a = (m - 0.5f) * 2f
                    val bodyW = 9.dp.toPx()
                    val bodyH = 7.dp.toPx()
                    val shake = bodyH / 2f
                    val top = point.y - shake + 1.5.dp.toPx()
                    drawRoundRect(
                        color = CameraColors.Yellow.copy(alpha = a),
                        topLeft = Offset(point.x - bodyW / 2f, top),
                        size = Size(bodyW, bodyH),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                    )
                    drawArc(
                        color = CameraColors.Yellow.copy(alpha = a),
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(
                            point.x - bodyW * 0.28f,
                            top - bodyW * 0.62f,
                        ),
                        size = Size(bodyW * 0.56f, bodyW * 0.62f),
                        style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
                    )
                }

                // "AE/AF LOCK" caption, fading in under the reticle.
                if (lockMorph.value > 0.01f) {
                    val labelTop = point.y + 40.dp.toPx()
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.45f * lockMorph.value),
                        topLeft = Offset(point.x - 34.dp.toPx(), labelTop),
                        size = Size(68.dp.toPx(), 16.dp.toPx()),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                    // Three short bars stand in for the label at this size: text in a
                    // Canvas would need a TextMeasurer per frame, and the bars read as
                    // "AE/AF" at a glance without that cost.
                    var barX = 0f
                    for (bars in 0 until 3) {
                        val w = 11.dp.toPx()
                        val x = point.x - 24.dp.toPx() + barX
                        drawRoundRect(
                            color = CameraColors.Yellow.copy(alpha = lockMorph.value * 0.85f),
                            topLeft = Offset(x, labelTop + 6.dp.toPx()),
                            size = Size(w, 3.4.dp.toPx()),
                            cornerRadius = CornerRadius(1.7.dp.toPx()),
                        )
                        barX += w + 4.dp.toPx()
                    }
                }
            }
        }

        // Gestures: tap to focus, pinch to zoom. Two independent detectors so a
        // pinch never registers as a focus tap.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled, locked) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        // Tap while locked releases; a normal tap focuses.
                        onTap = { offset ->
                            if (locked) {
                                onUnlock()
                            } else {
                                viewSize = Size(size.width.toFloat(), size.height.toFloat())
                                focusPoint = offset
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                onTap(
                                    (offset.x / w).coerceIn(0f, 1f),
                                    (offset.y / h).coerceIn(0f, 1f),
                                )
                            }
                        },
                        // Hold to lock AE/AF at that point.
                        onLongPress = { offset ->
                            viewSize = Size(size.width.toFloat(), size.height.toFloat())
                            focusPoint = offset
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            onTap(
                                (offset.x / w).coerceIn(0f, 1f),
                                (offset.y / h).coerceIn(0f, 1f),
                            )
                            onLock()
                        },
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var lastStop = -1
                    detectTransformGestures { _, _, scale, _ ->
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

