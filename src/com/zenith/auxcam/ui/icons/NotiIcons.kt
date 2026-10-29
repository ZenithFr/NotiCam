package com.zenith.auxcam.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-built SF-Symbols-style icon set.
 *
 * Why not vector drawables or `material-icons-extended`?
 *  - **Quality:** each glyph is drawn on a 24 unit grid with a 2.4 unit optical
 *    margin, a consistent 1.7 stroke and round caps, so it stays crisp and
 *    optically balanced at 18–24 dp on the CMF Phone 2 Pro's 402 ppi panel.
 *  - **No clipping:** an `ImageVector` is resolution independent and is rendered
 *    inside an exact `Icon` box — there is no bitmap padding to crop when the
 *    surrounding control animates its scale.
 *  - **Size:** the extended Material icon set is ~30 MB of classes for the nine
 *    glyphs we actually use.
 */
object NotiIcons {

    private const val STROKE = 1.7f

    /** Draws an unfilled, round-capped stroke path. `Icon` tints it. */
    private fun ImageVector.Builder.strokePath(
        width: Float = STROKE,
        alpha: Float = 1f,
        block: PathBuilder.() -> Unit,
    ) = path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeAlpha = alpha,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

    private fun ImageVector.Builder.fillPath(block: PathBuilder.() -> Unit) = path(
        fill = SolidColor(Color.White),
        stroke = null,
        pathBuilder = block,
    )

    /** Cubic circle approximation — avoids relying on arc/oval path helpers. */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        val k = r * 0.5523f
        moveTo(cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        close()
    }

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    private fun PathBuilder.bolt() {
        moveTo(13.6f, 2.4f)
        lineTo(6.0f, 13.4f)
        lineTo(11.0f, 13.4f)
        lineTo(9.9f, 21.6f)
        lineTo(17.8f, 10.6f)
        lineTo(12.7f, 10.6f)
        close()
    }

    /** Filled bolt: flash on. */
    val FlashOn: ImageVector = builder("FlashOn").apply {
        fillPath { bolt() }
    }.build()

    /** Outlined bolt with a slash: flash off. */
    val FlashOff: ImageVector = builder("FlashOff").apply {
        strokePath(alpha = 0.85f) { bolt() }
        strokePath(alpha = 0.85f) {
            moveTo(4.0f, 3.8f)
            lineTo(20.0f, 20.2f)
        }
    }.build()

    /** Bolt + stroked A: automatic flash. */
    val FlashAuto: ImageVector = builder("FlashAuto").apply {
        strokePath(alpha = 0.9f) {
            moveTo(11.4f, 2.6f)
            lineTo(4.4f, 13.2f)
            lineTo(8.9f, 13.2f)
            lineTo(7.9f, 20.9f)
            lineTo(14.6f, 10.9f)
            lineTo(10.1f, 10.9f)
            close()
        }
        strokePath {
            moveTo(14.9f, 21.2f)
            lineTo(17.8f, 13.6f)
            lineTo(20.7f, 21.2f)
            moveTo(15.9f, 18.4f)
            lineTo(19.7f, 18.4f)
        }
    }.build()

    /** Three sliders: the Pro sheet. */
    val Sliders: ImageVector = builder("Sliders").apply {
        strokePath {
            // row 1
            moveTo(2.9f, 6.9f); lineTo(6.7f, 6.9f)
            moveTo(12.1f, 6.9f); lineTo(21.1f, 6.9f)
            // row 2
            moveTo(2.9f, 12.0f); lineTo(13.6f, 12.0f)
            moveTo(19.0f, 12.0f); lineTo(21.1f, 12.0f)
            // row 3
            moveTo(2.9f, 17.1f); lineTo(8.3f, 17.1f)
            moveTo(13.7f, 17.1f); lineTo(21.1f, 17.1f)
        }
        strokePath {
            circle(9.4f, 6.9f, 2.25f)
            circle(16.3f, 12.0f, 2.25f)
            circle(11.0f, 17.1f, 2.25f)
        }
    }.build()

    /** X mark: closes the Pro sheet. */
    val Close: ImageVector = builder("Close").apply {
        strokePath(width = 1.9f) {
            moveTo(6.2f, 6.2f); lineTo(17.8f, 17.8f)
            moveTo(17.8f, 6.2f); lineTo(6.2f, 17.8f)
        }
    }.build()

    /** Two chasing arrows: switch lens. */
    val FlipCamera: ImageVector = builder("FlipCamera").apply {
        strokePath {
            moveTo(4.6f, 10.3f)
            curveTo(5.4f, 6.0f, 9.1f, 3.3f, 13.3f, 3.7f)
            moveTo(11.0f, 1.5f); lineTo(13.5f, 3.7f); lineTo(11.0f, 5.9f)
        }
        strokePath {
            moveTo(19.4f, 13.7f)
            curveTo(18.6f, 18.0f, 14.9f, 20.7f, 10.7f, 20.3f)
            moveTo(13.0f, 22.5f); lineTo(10.5f, 20.3f); lineTo(13.0f, 18.1f)
        }
    }.build()

    /** Aperture blades: the f-stop control. */
    val Aperture: ImageVector = builder("Aperture").apply {
        strokePath(width = 1.6f) { circle(12f, 12f, 8.4f) }
        strokePath(width = 1.4f) {
            moveTo(12f, 12f); lineTo(19.3f, 7.8f)
            moveTo(12f, 12f); lineTo(19.3f, 16.2f)
            moveTo(12f, 12f); lineTo(12f, 20.4f)
            moveTo(12f, 12f); lineTo(4.7f, 16.2f)
            moveTo(12f, 12f); lineTo(4.7f, 7.8f)
            moveTo(12f, 12f); lineTo(12f, 3.6f)
        }
    }.build()

    /** Photo tile with a horizon: gallery. */
    val Gallery: ImageVector = builder("Gallery").apply {
        strokePath(width = 1.6f) {
            moveTo(4.0f, 5.6f)
            lineTo(20.0f, 5.6f)
            lineTo(20.0f, 18.4f)
            lineTo(4.0f, 18.4f)
            close()
        }
        strokePath(width = 1.6f) {
            moveTo(5.3f, 16.6f)
            lineTo(9.8f, 12.0f)
            lineTo(12.9f, 15.1f)
            lineTo(15.2f, 12.9f)
            lineTo(18.6f, 16.3f)
        }
        fillPath {
            circle(15.3f, 8.7f, 1.5f)
        }
    }.build()

    /** Rule-of-thirds grid. */
    val Grid: ImageVector = builder("Grid").apply {
        strokePath(width = 1.5f) {
            moveTo(3.6f, 3.6f)
            lineTo(20.4f, 3.6f)
            lineTo(20.4f, 20.4f)
            lineTo(3.6f, 20.4f)
            close()
        }
        strokePath(width = 1.2f, alpha = 0.9f) {
            moveTo(9.2f, 3.6f); lineTo(9.2f, 20.4f)
            moveTo(14.8f, 3.6f); lineTo(14.8f, 20.4f)
            moveTo(3.6f, 9.2f); lineTo(20.4f, 9.2f)
            moveTo(3.6f, 14.8f); lineTo(20.4f, 14.8f)
        }
    }.build()

    /** Clock: capture timer. */
    val Timer: ImageVector = builder("Timer").apply {
        strokePath(width = 1.6f) { circle(12f, 13f, 7.4f) }
        strokePath(width = 1.6f) {
            moveTo(12f, 13f); lineTo(12f, 9.2f)
            moveTo(9.6f, 2.6f); lineTo(14.4f, 2.6f)
            moveTo(12f, 2.6f); lineTo(12f, 5.4f)
        }
    }.build()

    /** Target crosshair: manual focus. */
    val Focus: ImageVector = builder("Focus").apply {
        strokePath(width = 1.6f) {
            moveTo(5f, 9f); lineTo(5f, 5f); lineTo(9f, 5f)
            moveTo(15f, 5f); lineTo(19f, 5f); lineTo(19f, 9f)
            moveTo(5f, 15f); lineTo(5f, 19f); lineTo(9f, 19f)
            moveTo(15f, 19f); lineTo(19f, 19f); lineTo(19f, 15f)
        }
        fillPath {
            circle(12f, 12f, 2f)
        }
    }.build()
}
