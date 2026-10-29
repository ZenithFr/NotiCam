package com.zenith.auxcam.ui.effects

import android.graphics.Bitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
 * Matte grain — the *only* surface texture in the chrome.
 *
 * This replaces the previous frosted-glass pass, which painted a white vertical
 * gradient along the top edge. That gradient is a specular highlight, and a
 * specular highlight is what makes a surface read as glass: bright where the
 * "light" hits, falling off away from it. Remove it and the same surface reads as
 * dyed film instead — flat, uniform, diffuse.
 *
 * So what is left is deliberately the opposite of glassmorphism:
 *  • **no sheen** — the surface is the same value top to bottom;
 *  • **no rim** — callers draw no hairline border (a bright 0.5 dp edge is the
 *    other half of the glass look);
 *  • **no blur** — nothing samples the backdrop, so nothing is "frosted";
 *  • **grain only** — a fine, evenly scattered speckle, which is what a matte
 *    (diffuse) surface actually gives you: micro-scatter, not reflection.
 *
 * The tile is baked once per process and tiled by the GPU, so each surface costs a
 * single `drawRect` per frame. A dozen of them still render at 60 fps.
 */
private const val TILE = 64

private val grainTile: ImageBitmap by lazy {
    val random = Random(7)
    val pixels = IntArray(TILE * TILE)
    for (i in pixels.indices) {
        // Dense enough to read as a matte tooth, sparse enough to never look dirty.
        val a = if (random.nextInt(100) < 62) random.nextInt(8, 28) else 0
        val v = random.nextInt(150, 255)
        pixels[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Repeated GPU-tiled grain, remembered for the composition. */
@Composable
fun rememberMatteGrain(): ShaderBrush =
    remember { ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated)) }

/**
 * Scatters [grain] over whatever the caller already drew.
 *
 * Apply **after** the flat shade and the content, and pass the same [shape] the
 * background uses — the grain is clipped to the outline, so a circle never gets
 * speckle in its corners.
 *
 * [strength] scales the speckle, not the surface: a selected (white) chip passes a
 * low value so it stays clean white, while a clear disc passes 1f so the tooth is
 * what tells you there is a surface there at all.
 */
fun Modifier.matte(
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

    // One flat pass. No gradient, so the surface has no direction of light.
    clipPath(path) {
        drawRect(brush = grain, alpha = 0.8f * strength)
    }
}
