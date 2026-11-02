package com.zenith.auxcam.ui.effects

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Neutral surface diffusion only. No sampled photo tint, blur or scene grading. */
@Composable
fun SceneAtmosphere(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.055f), Color.Transparent),
            center = Offset(size.width * 0.5f, 0f), radius = size.maxDimension * 0.8f))
    }
}
