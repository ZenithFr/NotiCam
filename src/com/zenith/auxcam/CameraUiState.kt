package com.zenith.auxcam

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** Capture modes exposed by the carousel. */
enum class CaptureMode(val label: String) {
    Video("Video"),
    Photo("Photo");
}

/** Flash request state, mapped 1:1 onto `CONTROL_AE_MODE`. */
enum class FlashMode { Off, Auto, On }

/** Output resolution. `High` is the 50 MP binned-off mode of the CMF Phone 2 Pro. */
enum class Resolution(val label: String, val longEdge: Int) {
    Standard("12MP", 4080),
    High("50MP", 8160);

    fun next(): Resolution = if (this == Standard) High else Standard
}

/**
 * Viewfinder framing. `FourThree` is the sensor's native ratio and therefore the
 * default; the others crop the framebuffer (they never stretch it).
 */
enum class FrameRatio(val label: String, val aspect: Float?) {
    Square("1:1", 1f),
    FourThree("4:3", 4f / 3f),
    Widescreen("16:9", 16f / 9f),
    /** Fills the available slot, cropping as needed. */
    Full("FULL", null);

    fun next(): FrameRatio = entries[(ordinal + 1) % entries.size]
}

/**
 * Immutable snapshot of everything the Compose UI renders. Held in a single
 * `MutableState` inside [CameraController] so recomposition is granular and the
 * composables stay side-effect free.
 */
@Immutable
data class CameraUiState(
    val mode: CaptureMode = CaptureMode.Photo,
    val bokehEnabled: Boolean = false,
    val bokehModel: BokehModel = BokehModel.MediaPipeAuto,
    val bokehModelReady: Boolean = false,
    val bokehLiveSuspended: Boolean = false,
    val bokehModelError: String? = null,
    val bokehModelStatus: String = "Model loads on first portrait frame or when checks run",
    val bokehChecksRunning: Boolean = false,
    val bokehCheckStatus: String = "No model checks run yet",
    val bokehCheckResults: List<BokehCheckResult> = emptyList(),
    /** The privileged main camera's advertised full-resolution JPEG size. */
    val highResolutionWidth: Int = 0,
    val highResolutionHeight: Int = 0,
    val highResolutionChecked: Boolean = false,
    val flash: FlashMode = FlashMode.Off,
    val zoom: Float = 1f,
    /** Viewfinder policy only; none of these values select the saved JPEG size. */
    val previewWidth: Int = DeviceProfile.COLOR_WIDTH,
    val previewHeight: Int = DeviceProfile.COLOR_HEIGHT,
    val previewTargetFps: Int = 30,
    val videoTargetFps: Int = 30,
    val previewMeasuredFps: Float = 0f,
    val previewFallback: String = "Checking advertised 60 fps support",
    val zoomBlendImage: ImageBitmap? = null,
    val zoomBlendReady: Boolean = false,
    val zoomBlendScale: Float = 1f,
    val zoomBlendToken: Int = 0,
    val zoomBlendPending: Boolean = false,

    /**
     * Aperture, as a real f-number. Continuous: the slider interpolates
     * logarithmically between [CameraController.FSTOPS] entries, so dragging is
     * smooth instead of stepping through 22 detents.
     */
    val fStop: Float = 1.8f,
    /** Exposure compensation in EV (-4f..+4f), also continuous. */
    val ev: Float = 0f,

    val grid: Boolean = false,
    val framingGuide: FramingGuide = FramingGuide.Thirds,
    val sensorControls: SensorControlCapabilities = SensorControlCapabilities(),
    val sensorControlsLoaded: Boolean = false,
    val manualExposure: Boolean = false,
    val sensorIso: Int = 100,
    val sensorExposureNs: Long = 8_000_000L,
    val whiteBalance: WhiteBalancePreset = WhiteBalancePreset.Auto,
    val sensorControlStatus: String = "Checking current lens controls…",

    val raw: Boolean = false,
    val rawFrames: Int = 1,
    val rawSensorWidth: Int = 0,
    val rawSensorHeight: Int = 0,
    val rawCapabilityChecked: Boolean = false,
    val timerSeconds: Int = 0,

    /** 12MP vs 50MP output. */
    val resolution: Resolution = Resolution.Standard,

    /** Viewfinder framing, default 4:3 (sensor native). */
    val frameRatio: FrameRatio = FrameRatio.FourThree,

    /** AE/AF held: the reticle becomes a lock badge until the user taps again. */
    val aeAfLocked: Boolean = false,

    /** Manual focus mode active. */
    val manualFocus: Boolean = false,
    /** 0.0 = infinity, 1.0 = closest / macro. */
    val focusFraction: Float = 0f,

    val settingsOpen: Boolean = false,
    val galleryOpen: Boolean = false,
    val videoStarting: Boolean = false,
    val videoRecording: Boolean = false,
    val videoElapsedSeconds: Long = 0,
    val videoHasAudio: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val photoWidth: Int = DeviceProfile.STILL_WIDTH,
    val photoHeight: Int = DeviceProfile.STILL_HEIGHT,

    val status: String = "",
    val telemetry: String = "",
    val sessionReady: Boolean = false,
    val captureBusy: Boolean = false,

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
    val bokehActive: Boolean
        get() = mode == CaptureMode.Photo && bokehEnabled && resolution == Resolution.Standard

    val supportsHighResolution: Boolean
        get() = CaptureOutput.is50MpSize(highResolutionWidth, highResolutionHeight)

    val previewTransitioning: Boolean
        get() = zoomBlendPending || zoomBlendImage != null

    val canAdjustSensorControls: Boolean
        get() = sessionReady && sensorControlsLoaded && !captureBusy && !previewTransitioning && !bokehChecksRunning && !videoStarting && !videoRecording

    val maxManualExposureNs: Long
        get() = SensorControlPolicy.maxExposure(sensorControls, mode == CaptureMode.Video, videoTargetFps)

    val manualExposureAvailable: Boolean
        get() = sensorControls.hasManualExposure && resolution == Resolution.Standard && maxManualExposureNs > 0L &&
            SensorControlPolicy.liveExposure(sensorExposureNs, sensorControls, if (mode == CaptureMode.Video) videoTargetFps else previewTargetFps) > 0L

    val supportsRaw: Boolean
        get() = rawCapabilityChecked && maxRawFrames > 0

    val maxRawFrames: Int
        get() = RawFrameMatcher.maxFramesForSize(rawSensorWidth, rawSensorHeight)

    val canTakePhoto: Boolean
        get() = mode == CaptureMode.Photo && sessionReady && !captureBusy && !previewTransitioning && !bokehChecksRunning && (!raw || supportsRaw)

    val canUseShutter: Boolean
        get() = if (mode == CaptureMode.Photo) canTakePhoto
        else videoRecording || (sessionReady && !captureBusy && !videoStarting && !previewTransitioning && !bokehChecksRunning)

    val photoSizeLabel: String
        get() {
            val pixels = photoWidth.toLong() * photoHeight
            return if (pixels in 11_000_000L..14_000_000L) "12 MP"
                else String.format(java.util.Locale.US, "%.0f MP", pixels.toDouble() / 1_000_000)
        }

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

    /**
     * Aspect the viewfinder box must use, in *screen* terms (portrait), or `null`
     * when the caller should simply fill the slot.
     */
    val frameAspect: Float?
        get() = frameRatio.aspect?.let { if (DeviceProfile.PREVIEW_ROTATION_90) 1f / it else it }

    /** Simulated aperture applies only to optional Portrait processing. */
    val apertureVisible: Boolean
        get() = bokehActive

    val focalLabel: String
        get() = String.format("%.0f MM", DeviceProfile.FOCAL_MAIN_MM * zoom)

    val quickApertureVisible: Boolean
        get() = bokehActive && !settingsOpen

    val focusLabel: String
        get() {
            if (!manualFocus) return if (aeAfLocked) "LOCKED" else "AUTO"
            if (focusFraction <= 0.03f) return "∞"
            if (focusFraction >= 0.97f) return "MACRO"
            // The HAL maps this fraction to lens diopters; without its actual
            // minimum-focus metadata a metre label would be fabricated.
            return String.format(java.util.Locale.US, "%.0f%%", focusFraction * 100f)
        }

    val quickFocusVisible: Boolean
        get() = manualFocus && mode == CaptureMode.Photo
}
