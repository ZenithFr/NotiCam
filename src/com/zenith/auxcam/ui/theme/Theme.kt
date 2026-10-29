package com.zenith.auxcam.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apple Camera tokens, expressed as Compose values.
 *
 * Everything the chrome draws comes from here so the capture surface cannot
 * drift away from the design language: one accent (systemYellow), translucent
 * near-black glass, hairline separators and an SF-style type ramp.
 */
object CameraColors {
    /** iOS systemYellow (dark appearance) — selection only. */
    val Yellow = Color(0xFFFFD60A)
    /** iOS systemRed — recording only. */
    val Red = Color(0xFFFF453A)
    /** iOS systemGreen — reserved for future "ready" states. */
    val Green = Color(0xFF30D158)

    val Label = Color.White
    val LabelSecondary = Color(0xA6FFFFFF)
    val LabelTertiary = Color(0x66FFFFFF)
    val LabelQuaternary = Color(0x40FFFFFF)
    val OnLight = Color.Black

    /** 55 % #1C1C1E — glass controls floating over the scene. */
    val Glass = Color(0x8C1C1C1E)
    /** 95 % #1C1C1E — the Pro sheet. */
    val GlassStrong = Color(0xF21C1C1E)
    /** Pressed / ripple tint. */
    val GlassPressed = Color(0x33FFFFFF)

    val Separator = Color(0x1FFFFFFF)
    val SeparatorStrong = Color(0x33FFFFFF)
    val SliderTrack = Color(0x3DFFFFFF)
    val SliderFill = Color.White
    val Scrim = Color(0x99000000)
    val Black = Color.Black
}

/** 8-pt spacing grid. */
object CameraSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp

    /** Minimum touch target, per HIG. */
    val touch = 48.dp
    val iconButton = 40.dp
    val iconSize = 21.dp
    val gutter = 16.dp
    val gutterWide = 24.dp
    val capsuleHeight = 34.dp
    val panelRadius = 26.dp
    val shutterVisual = 72.dp
    /** The shutter composable is larger than its disc so the capture pulse ring
     *  always has room to expand — this is what stops the animation clipping. */
    val shutterTouch = 96.dp
    val gallery = 52.dp
}

/**
 * Every spring and tween in the app, in one place.
 *
 * Rules of thumb baked into these numbers:
 *  • **press down** is fast and stiff (~120 ms to settle) so the control answers
 *    the finger immediately;
 *  • **release** is slower and slightly bouncy, which is where the iOS "lift"
 *    feel comes from;
 *  • **selection** (chips, modes) is a medium spring — visible travel, no wobble;
 *  • **slides** (Pro sheet, dial) are heavily damped so panels never overshoot
 *    into their neighbours;
 *  • physical detents (zoom wheel) use a low-bouncy, low-stiffness spring so the
 *    indicator keeps a little momentum like a real lens barrel.
 *
 * Nothing here is animated with `LinearEasing`, and no control uses a default
 * `animate*AsState()` spec — that is what made the previous pass feel mechanical.
 */
object CameraMotion {

    /** Press compression. Crisp, no visible wobble. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessHigh)

    /** Finger-up return. The bounce is subtle but it is what reads as "iOS". */
    fun <T> release(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Selection / colour / mode changes. */
    fun <T> select(): SpringSpec<T> = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

    /** Panels and overlays sliding into place — damped, never overshoots a neighbour. */
    fun <T> slide(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** Zoom wheel / radial indicator: keeps a little momentum. */
    fun <T> wheel(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** Snapping the wheel onto a lens stop. */
    fun <T> detent(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)

    /** Short opacity transitions. Fades are always faster than moves. */
    fun fade(durationMillis: Int = 140): TweenSpec<Float> = tween(durationMillis)

    const val PressedScale = 0.90f
    const val PressedAlpha = 0.72f
}

object CameraType {
    private val sf = FontFamily.SansSerif

    val Mode = TextStyle(
        fontFamily = sf,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.9.sp,
    )

    val Lens = TextStyle(
        fontFamily = sf,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.24.sp,
    )

    val Section = TextStyle(
        fontFamily = sf,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.3.sp,
    )

    val Value = TextStyle(
        fontFamily = sf,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
    )

    val Caption = TextStyle(
        fontFamily = sf,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    )

    val Telemetry = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 0.2.sp,
    )

    val Dial = TextStyle(
        fontFamily = sf,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        letterSpacing = 0.5.sp,
    )

    val DialCaption = TextStyle(
        fontFamily = sf,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    )
}

private val NotiCamColorScheme = darkColorScheme(
    primary = CameraColors.Yellow,
    onPrimary = CameraColors.OnLight,
    secondary = CameraColors.Label,
    background = CameraColors.Black,
    onBackground = CameraColors.Label,
    surface = CameraColors.Black,
    onSurface = CameraColors.Label,
    error = CameraColors.Red,
)

@Composable
fun NotiCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NotiCamColorScheme,
        typography = Typography(),
        content = content,
    )
}
