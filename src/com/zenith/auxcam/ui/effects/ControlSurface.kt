package com.zenith.auxcam.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.zenith.auxcam.ui.theme.CameraColors

/** Flat matte film: retain the control surface, without rims, sheen or backdrop blur. */
fun Modifier.cameraMatte(shape: Shape = CircleShape): Modifier =
    background(CameraColors.ControlSurface, shape)
