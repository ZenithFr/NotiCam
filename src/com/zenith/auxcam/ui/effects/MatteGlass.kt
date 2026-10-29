package com.zenith.auxcam.ui.effects

import android.graphics.Bitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.random.Random

/**
 * Frosted, *textured* glass.
 *
 * Flat translucency reads as a plain tinted rectangle. Real frosted glass gives two
 * more cues: a faint sheen that follows the top edge, and a micro-grain that breaks
 * up the perfectly flat fill. Both are drawn here from a single pre-baked 64x64
 * noise tile, so each glass surface costs one extra `drawRect` — cheap enough that
 * a dozen of them still render at 60 fps.
 *
 * The tile is built once per process (not per composition, not per frame).
 */
private const val TILE = 64

private val grainTile: ImageBitmap by lazy {
    val random = Random(7)
    val pixels = IntArray(TILE * TILE)
    for (i in pixels.indices) {
        // Sparse and very low alpha: reads as grain, never as dirt.
        val a = if (random.nextInt(100) < 58) random.nextInt(6, 22) else 0
        val v = random.nextInt(120, 255)
        pixels[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Repeated GPU-tiled grain, remembered for the composition. */
@Composable
fun rememberMatteGrain(): ShaderBrush =
    remember { ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated)) }

/**
 * Paints the frosted texture over whatever the caller already drew.
 *
 * Apply **after** the background so the grain sits on the glass, not under it, and
 * pass the same [shape] the background uses — the texture is clipped to the outline,
 * so a circle never gets grain in its corners.
 */
fun Modifier.matteGlass(
    grain: ShaderBrush,
    shape: Shape = CircleShape,
    strength: Float = 1f,
): Modifier = drawWithContent {
    drawContent()

    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply {
        when (outline) {
            is Outline.Generic -> addPath(outline.path)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Rectangle -> addRect(outline.rect)
        }
    }

    clipPath(path) {
        // Sheen from the top edge, like light catching the glass.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.10f * strength),
                    Color.White.copy(alpha = 0.02f * strength),
                    Color.Transparent,
                ),
            ),
        )
        drawRect(brush = grain, alpha = 0.5f * strength)
    }
}
