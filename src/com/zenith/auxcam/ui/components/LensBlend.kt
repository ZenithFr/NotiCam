package com.zenith.auxcam.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.CameraZoom

/** Presentation-only two-layer bridge. Never changes a SurfaceView's buffer size
 * or camera crop/still settings. Keep the live content at a stable composition
 * position: wrapping/unwrapping it when a snapshot appears would recreate its surface.
 */
@Composable
fun LensBlend(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
    liveContent: @Composable () -> Unit,
) {
    val image = state.zoomBlendImage
    val ratio = state.zoomBlendScale.coerceIn(.3f, 3.4f)
    val progress = remember(image, state.zoomBlendToken) { Animatable(0f) }
    val introScale = remember(image, state.zoomBlendToken) { Animatable(1f) }
    var snapshotStartScale by remember(image, state.zoomBlendToken) { mutableFloatStateOf(1f) }

    LaunchedEffect(image, state.zoomBlendReady, ratio) {
        if (image != null) {
            if (!state.zoomBlendReady) {
                // Retain the existing zoom-in approach; an outgoing narrow frame
                // cannot reveal wider pixels until the new camera is available.
                introScale.animateTo(maxOf(1f, ratio), tween(320, easing = FastOutSlowInEasing))
            } else {
                repeat(2) { withFrameNanos { } } // QueuePresent is not a display latch.
                snapshotStartScale = introScale.value
                // One clock drives old scale, new scale and alpha. No delayed,
                // independent shrink that exposes an already-wide live image.
                progress.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                actions.onZoomBlendFinished(state.zoomBlendToken)
            }
        }
    }

    Box(modifier.clipToBounds()) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val liveScale = if (image == null) 1f else
                CameraZoom.incomingPreviewScale(ratio, progress.value, snapshotStartScale)
            scaleX = liveScale
            scaleY = liveScale
        }) {
            liveContent()
        }
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = 1f - progress.value
                    val oldScale = if (ratio < 1f && progress.value > 0f)
                        CameraZoom.outgoingSnapshotScale(ratio, progress.value, snapshotStartScale)
                    else introScale.value + (ratio - introScale.value) * progress.value
                    scaleX = oldScale
                    scaleY = oldScale
                })
        }
    }
}
