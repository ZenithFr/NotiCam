package com.zenith.auxcam.ui.effects

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.zenith.auxcam.ui.theme.CameraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tiny, saved-thumbnail palette only. Never reads live frames or touches captured pixels.
 * Soft radial washes suggest the reference's artwork atmosphere without claiming to
 * blur/refract a native SurfaceView. Sample once per image, not once per UI frame.
 */
@Composable
fun SceneAtmosphere(image: ImageBitmap?, modifier: Modifier = Modifier) {
    val target by produceState(CameraColors.Atmosphere, image) {
        value = withContext(Dispatchers.Default) { thumbnailTone(image) }
    }
    val tone by animateColorAsState(target, tween(900), label = "scene-atmosphere")
    Canvas(modifier) {
        drawRect(Brush.radialGradient(
            listOf(tone.copy(alpha = .36f), Color.Transparent),
            center = Offset(size.width * .15f, size.height * .15f),
            radius = size.maxDimension * .75f,
        ))
        drawRect(Brush.radialGradient(
            listOf(tone.copy(alpha = .24f), Color.Transparent),
            center = Offset(size.width * .95f, size.height * .95f),
            radius = size.maxDimension * .65f,
        ))
    }
}

private fun thumbnailTone(image: ImageBitmap?): Color {
    if (image == null) return CameraColors.Atmosphere
    var ownedCopy: Bitmap? = null
    return try {
        val original = image.asAndroidBitmap()
        // MediaStore may return a hardware thumbnail. Never mutate/recycle the caller's bitmap.
        val bitmap = if (original.config == Bitmap.Config.HARDWARE) {
            original.copy(Bitmap.Config.ARGB_8888, false)?.also { ownedCopy = it }
                ?: return CameraColors.Atmosphere
        } else original
        var red = 0.0; var green = 0.0; var blue = 0.0; var weight = 0.0
        for (y in 0 until 12) for (x in 0 until 12) {
            val pixel = bitmap.getPixel(x * bitmap.width / 12, y * bitmap.height / 12)
            if ((pixel ushr 24) < 128) continue
            val r = (pixel ushr 16 and 255) / 255.0
            val g = (pixel ushr 8 and 255) / 255.0
            val b = (pixel and 255) / 255.0
            val light = (r + g + b) / 3.0
            if (light < .06 || light > .94) continue
            val w = .08 + maxOf(r, g, b) - minOf(r, g, b)
            red += r * w; green += g * w; blue += b * w; weight += w
        }
        if (weight == 0.0) CameraColors.Atmosphere
        else Color((red / weight).toFloat(), (green / weight).toFloat(), (blue / weight).toFloat())
    } catch (_: Exception) {
        CameraColors.Atmosphere
    } finally {
        ownedCopy?.recycle()
    }
}
