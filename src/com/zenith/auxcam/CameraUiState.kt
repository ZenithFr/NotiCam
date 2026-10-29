package com.zenith.auxcam

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** Capture modes exposed by the carousel. */
enum class CaptureMode(val label: String) {
    Photo("Photo"),
    Portrait("Portrait"),
    Pro("Pro");

    /** Pro captures with high-quality noise reduction; the others use fast NR. */
    val highQualityNr: Boolean get() = this == Pro
}

/** Flash request state, mapped 1:1 onto `CONTROL_AE_MODE`. */
enum class FlashMode { Off, Auto, On }

/**
 * Immutable snapshot of everything the Compose UI renders. Held in a single
 * `MutableState` inside [CameraController] so recomposition is granular and the
 * composables stay side-effect free.
 */
@Immutable
data class CameraUiState(
    val mode: CaptureMode = CaptureMode.Photo,
    val flash: FlashMode = FlashMode.Off,
    val zoom: Float = 1f,

    /**
     * Aperture, as a real f-number. Continuous: the slider interpolates
     * logarithmically between [CameraController.FSTOPS] entries, so dragging is
     * smooth instead of stepping through 22 detents.
     */
    val fStop: Float = 1.8f,
    /** Exposure compensation in EV (-4f..+4f), also continuous. */
    val ev: Float = 0f,

    val grid: Boolean = false,
    val raw: Boolean = false,
    val timerSeconds: Int = 0,

    val proSheetOpen: Boolean = false,

    val status: String = "",
    val telemetry: String = "",
    val sessionReady: Boolean = false,

    /** Increments on every shutter press; drives the bloom + ring pulse. */
    val capturePulse: Int = 0,
    /** Increments whenever a finished still is pushed to the gallery tile. */
    val galleryPulse: Int = 0,
    val lastThumbnail: ImageBitmap? = null,

    val lensCount: Int = 1,
    val lensIndex: Int = 0,

    val focusNonce: Int = 0,
    val zoomDialVisible: Boolean = false,
) {
    /** Aperture shown by the sliders: 0 = open (1.4), 1 = closed (16). */
    val apertureFraction: Float
        get() {
            val lo = CameraController.FSTOPS.first().toDouble()
            val hi = CameraController.FSTOPS.last().toDouble()
            return (kotlin.math.ln(fStop.coerceIn(lo.toFloat(), hi.toFloat()) / lo) /
                kotlin.math.ln(hi / lo)).toFloat().coerceIn(0f, 1f)
        }

    val fStopLabel: String
        get() = "ƒ " + if (fStop >= 9.95f) String.format("%.0f", fStop) else String.format("%.1f", fStop)

    val evLabel: String
        get() {
            if (kotlin.math.abs(ev) < 0.05f) return "0 EV"
            val sign = if (ev > 0) "+" else "−"
            return sign + String.format("%.1f", kotlin.math.abs(ev)) + " EV"
        }

    /** 0..1 position of the EV slider across its -4..+4 range. */
    val evFraction: Float
        get() = ((ev + 4f) / 8f).coerceIn(0f, 1f)

    /** True while the zoom surface is showing (pinch in progress or just ended). */
    val zoomActive: Boolean
        get() = zoomDialVisible

    val focalLabel: String
        get() = String.format("%.0f MM", DeviceProfile.FOCAL_MAIN_MM * zoom)

    val quickApertureVisible: Boolean
        get() = mode == CaptureMode.Portrait && !proSheetOpen
}
