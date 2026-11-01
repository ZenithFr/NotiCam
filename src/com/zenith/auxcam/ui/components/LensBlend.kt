package com.zenith.auxcam.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Short, presentation-only field-of-view match and dissolve, never used for saved media.
 * A single camera cannot eliminate scene motion, parallax or exposure differences;
 * retain the outgoing frame across session downtime, then reveal fresh incoming frames.
 */
@Composable
fun LensBlend(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val image = state.zoomBlendImage ?: return
    val alpha = remember(image) { Animatable(1f) }
    val scale = remember(image) { Animatable(1f) }
    LaunchedEffect(image, state.zoomBlendReady) {
        if (state.zoomBlendScale >= 1f || state.zoomBlendReady) launch {
            scale.animateTo(state.zoomBlendScale.coerceIn(.3f, 3.4f), tween(320, easing = FastOutSlowInEasing))
        }
        if (state.zoomBlendReady) {
            // QueuePresent is not display latch: allow the incoming frame to reach the display.
            repeat(2) { withFrameNanos { } }
            delay(80)
            alpha.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            actions.onZoomBlendFinished(state.zoomBlendToken)
        }
    }
    Box(modifier.clipToBounds()) {
        Image(image, contentDescription = null, contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            })
    }
}
