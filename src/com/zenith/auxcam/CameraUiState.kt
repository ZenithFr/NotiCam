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

    /** Index into [CameraController.FSTOPS]. */
    val fStopIndex: Int = 2,
    /** Exposure compensation in 1/3 EV steps (-12..+12). */
    val evSteps: Int = 0,

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
    val fStop: Float
        get() = CameraController.FSTOPS[fStopIndex.coerceIn(0, CameraController.FSTOPS.lastIndex)]

    val fStopLabel: String
        get() = "ƒ " + if (fStop >= 9.95f) String.format("%.0f", fStop) else String.format("%.1f", fStop)

    val evLabel: String
        get() {
            val ev = evSteps / 3f
            if (kotlin.math.abs(ev) < 0.05f) return "0 EV"
            val sign = if (ev > 0) "+" else "−"
            return sign + String.format("%.1f", kotlin.math.abs(ev)) + " EV"
        }

    val focalLabel: String
        get() = String.format("%.0f MM", DeviceProfile.FOCAL_MAIN_MM * zoom)

    val quickApertureVisible: Boolean
        get() = mode == CaptureMode.Portrait && !proSheetOpen
}
