package com.zenith.auxcam.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.zenith.auxcam.ui.theme.CameraColors

/** 90% see-through fill, readable 90%-opacity glyphs, tiled matte diffusion.
 * No decorative strokes or opaque selected cards. This does NOT claim backdrop
 * blur: Compose blur cannot sample the native camera SurfaceView in another layer.
 * Never blur the whole composable (which would blur its text and hit indicators).
 */
@Composable
fun Modifier.cameraGlass(shape: Shape = CircleShape, baseColor: Color = CameraColors.ControlSurface): Modifier =
    background(baseColor.copy(alpha = 0.10f), shape)
        .matte(rememberMatteGrain(), shape, strength = 0.22f)
