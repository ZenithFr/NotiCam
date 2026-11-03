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

/** Nothing-inspired, independently implemented neutral/matte tokens.
 * Reference: nothing.wiki/nothing/brand_reference (community guide).
 * No Nothing logos or proprietary fonts are bundled; system type is a fallback.
 */
object CameraColors {
    val Accent = Color.White
    /** Functional focus reticle; not the UI selection accent. */
    val Yellow = Color(0xFFFFC700)
    val Atmosphere = Color(0xFFB1B3B3)
    /** N-Red: functional recording/error accents, not a colored brand mark. */
    val Red = Color(0xFFC8102E)
    /** Neutral status token; do not imply a semantic pass just with color. */
    val Green = Color(0xFFB1B3B3)

    val Label = Color.White.copy(alpha = 0.90f)
    val LabelSecondary = Color(0xFFDCD7D2)
    val LabelTertiary = Color(0xFFB1B3B3)
    val LabelQuaternary = Color(0xFF777B7B)
    val OnLight = Color.Black

    /** Subtle inset fill, not a decorative outline. */
    val Matte = Color(0xFF222525)
    val ControlSurface = Color(0xE62A2D2D)
    /** Sheets need their own readable body over the native camera SurfaceView. */
    val MatteScrim = Color(0xFA191C1C)

    /** Internal hairline *between rows inside* a panel. Never a surface rim. */
    val Separator = Color(0x1FFFFFFF)
    val SliderTrack = Color(0x3DFFFFFF)
    val SliderFill = Color.White
    val Scrim = Color(0x99000000)
    val Black = Color(0xFF000000)

    // ── Settings page surfaces ─────────────────────────────────────────────
    // Elevation by lightness alone (one tone step per layer), never shadows.
    /** Full-screen settings ground, a hair above the camera's pure black. */
    val PageBackground = Color(0xFF0B0D0D)
    /** Grouped settings card, one tone step above the page. */
    val CardSurface = Color(0xFF161919)
    /** Inset control track / raised chip inside a card. */
    val CardRaised = Color(0xFF202424)
    /** Switch track when off. */
    val SwitchTrackOff = Color(0xFF2C3131)
    /** Destructive tint used only for delete-style actions. */
    val Danger = Color(0xFFC8102E)
}

/** 8-pt spacing grid. */
object CameraSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp

    /** Minimum interactive target: 48 dp; visual glyphs remain smaller. */
    val touch = 48.dp
    val iconButton = 36.dp
    val iconSize = 18.dp
    val gutter = 16.dp
    val gutterWide = 24.dp
    val capsuleHeight = 48.dp
    val panelRadius = 28.dp
    val shutterVisual = 68.dp
    /** The shutter composable is larger than its disc so the capture pulse ring
     *  always has room to expand — this is what stops the animation clipping. */
    val shutterTouch = 96.dp
    val gallery = 48.dp
    val railSlider = 44.dp
    val controlRadius = 16.dp

    // ── Settings page rhythm (8-pt grid) ───────────────────────────────────
    val pageGutter = 20.dp
    val cardRadius = 20.dp
    val cardPadding = 4.dp
    val rowMinHeight = 60.dp
    val sectionGap = 24.dp
    val rowIcon = 20.dp
}

/**
 * Every spring and tween in the app, in one place.
 *
 * Rules of thumb baked into these numbers:
 *  • **press down** is fast and stiff (~120 ms to settle) so the control answers
 *    the finger immediately;
 *  • **release** is slower and critically damped, keeping the matte controls
 *    quiet and readable;
 *  • **selection** (chips, modes) is a medium spring — visible travel, no wobble;
 *  • **slides** (controls sheet, dial) are heavily damped so panels never overshoot
 *    into their neighbours;
 *  • physical detents (zoom wheel) use a low-bouncy, low-stiffness spring so the
 *    indicator keeps a little momentum like a real lens barrel.
 *
 * Nothing here is animated with `LinearEasing`, and no control uses a default
 * `animate*AsState()` spec — that is what made the previous pass feel mechanical.
 */
object CameraMotion {

    /** Press compression. Crisp, no visible wobble. */
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessHigh)

    /** Finger-up return. Critically damped return; do not bounce into adjacent controls. */
    fun <T> release(): SpringSpec<T> = spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Selection / colour / mode changes. */
    fun <T> select(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

    /** Panels and overlays sliding into place — damped, never overshoots a neighbour. */
    fun <T> slide(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** Zoom wheel / radial indicator: keeps a little momentum. */
    fun <T> wheel(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** Snapping the wheel onto a lens stop. */
    fun <T> detent(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)

    /** Quick, critically damped retargeting for readouts and slider taps. */
    fun <T> fluid(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 650f)

    /** Short opacity transitions. Fades are always faster than moves. */
    fun fade(durationMillis: Int = 140): TweenSpec<Float> = tween(durationMillis)

    const val PressedScale = 0.90f
    const val PressedAlpha = 0.72f
}

object CameraType {
    private val sans = FontFamily.SansSerif

    val Title = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        letterSpacing = (-0.26).sp,
    )

    val Heading = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        letterSpacing = 0.2.sp,
    )

    /** Settings page title. */
    val PageTitle = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    )

    /** A settings row's primary label. */
    val RowTitle = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    )

    /** A settings row's supporting description. */
    val RowBody = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    )

    /** Sentence-case group label above a settings card. */
    val SectionLabel = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    )

    val Mode = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 0.15.sp,
    )

    val Lens = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.sp,
    )

    val Section = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.sp,
    )

    val Value = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    )

    val Caption = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.12.sp,
    )

    val Telemetry = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.sp,
    )

    val Dial = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        letterSpacing = 0.5.sp,
    )

    val DialCaption = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    )
}

private val NotiCamColorScheme = darkColorScheme(
    primary = CameraColors.Accent,
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
