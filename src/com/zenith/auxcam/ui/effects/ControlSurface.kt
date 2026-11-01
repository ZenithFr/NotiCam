package com.zenith.auxcam.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.zenith.auxcam.ui.theme.CameraColors

/** Translucent glass body and soft interior light. No border, rim or edge stroke.
 * Avoid backdrop blur across the native SurfaceView: captured pixels are untouched.
 */
fun Modifier.cameraGlass(shape: Shape = CircleShape, baseColor: Color = CameraColors.ControlSurface): Modifier =
    background(baseColor, shape).background(
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.035f), Color.Black.copy(alpha = 0.06f))),
        shape,
    )
