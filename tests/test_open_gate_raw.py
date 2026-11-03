"""Open-gate RAW contracts.

Open gate = the full-sensor Bayer readout written as a DNG, not a cropped/binned
"traditional" RAW. On a quad-bayer sensor the full-resolution RAW is frequently
advertised only in the maximum-resolution stream map, so the session must declare
SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION and use the maximum active-array domain.
These are source/contract checks, not a substitute for an on-device Camera2 capture.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/com/zenith/auxcam"


class OpenGateRaw(unittest.TestCase):
    def setUp(self):
        self.caps = (SRC / "CameraCapabilities.java").read_text()
        self.service = (SRC / "CameraUserService.java").read_text()
        self.controller = (SRC / "CameraController.kt").read_text()

    def test_discovery_finds_full_sensor_raw_in_the_maximum_resolution_map(self):
        # The default-map class gate and payload budget are preserved...
        raw = self.caps.split("static Size rawSize(", 1)[1].split("/** Pixel mode needed", 1)[0]
        for contract in ("REQUEST_AVAILABLE_CAPABILITIES_RAW",
                         "getOutputSizes(ImageFormat.RAW_SENSOR)",
                         "getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)",
                         "high != (pixels >= 45_000_000L)",
                         "RawFrameMatcher.maxFramesForSize"):
            self.assertIn(contract, raw)
        # ...and open gate additionally searches the maximum-resolution map.
        self.assertIn("SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION", raw)
        self.assertIn("if (high && Build.VERSION.SDK_INT >= 31)", raw)

    def test_pixel_mode_is_derived_only_from_an_advertised_size(self):
        mode = self.caps.split("static int rawPixelMode(", 1)[1].split("/** Normal Camera2", 1)[0]
        self.assertIn("SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION", mode)
        self.assertIn("return CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION;", mode)
        # Fail closed to DEFAULT when the size is not advertised in the max map.
        self.assertIn("return CaptureRequest.SENSOR_PIXEL_MODE_DEFAULT;", mode)
        self.assertIn("Build.VERSION.SDK_INT < 31", mode)

    def test_session_declares_the_pixel_mode_and_full_field_for_open_gate(self):
        self.assertIn("mStillPixelMode = CameraCapabilities.rawPixelMode(cm, colorPhysicalId, stillW, stillH);", self.service)
        self.assertIn("mRawOpenGate = true;", self.service)
        opened = self.service.split("void onDeviceOpened(", 1)[1].split("void onSessionConfigured(", 1)[0]
        self.assertIn("addSensorPixelModeUsed(mStillPixelMode)", opened)
        self.assertIn("SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION", opened)
        burst = self.service.split("private void startRawCapture", 1)[1].split("public void triggerStill(long shutterCommandNs, boolean", 1)[0]
        self.assertIn("b.set(CaptureRequest.SENSOR_PIXEL_MODE, mStillPixelMode);", burst)
        self.assertIn("applyCropAndExposure(b, mRawOpenGate);", burst)
        # Open gate keeps the full active array (no digital crop) and resets on close.
        self.assertIn("if (mRawOpenGate) {", self.service)
        self.assertIn("mCropRegion = new Rect(mActiveArray);", self.service)
        self.assertIn("mRawOpenGate = false;", self.service)

    def test_controller_defaults_to_open_gate_and_locks_the_full_field(self):
        self.assertIn(".version(12)", self.controller)
        toggle = self.controller.split("override fun onRawToggle(", 1)[1].split("override fun", 1)[0]
        self.assertIn("val openGate = state.rawHighWidth > 0 && state.rawHighHeight > 0", toggle)
        self.assertIn("Enabling open-gate RAW (full sensor)…", toggle)
        self.assertIn("Open gate RAW keeps the full sensor field", self.controller)
        reader = self.controller.split("private fun createStillReader", 1)[1].split("private fun", 1)[0]
        self.assertIn("getRawConfiguration(currentLens, state.resolution == Resolution.High)", reader)
        self.assertIn("RawFrameMatcher.maxFramesForSize(width, height).coerceAtLeast(1)", reader)

    def test_ui_names_the_mode_open_gate_without_claiming_merged_hdr(self):
        settings = (SRC / "ui/components/CameraSettings.kt").read_text()
        button = (SRC / "ui/components/RawCaptureButton.kt").read_text()
        self.assertIn('title = "Open gate RAW"', settings)
        self.assertIn('title = "Sensor readout"', settings)
        self.assertIn('options = listOf("Binned", "Open gate")', settings)
        self.assertIn("Open gate RAW · full-sensor readout", button)
        self.assertIn('TogglePill("Open gate"', button)
        self.assertIn("not HDR/merged", button)
        companion = (ROOT / "design/index.html").read_text()
        self.assertIn("Open gate RAW", companion)
        self.assertIn("OPEN GATE RAW", companion)

    def test_open_gate_falls_back_to_native_sensor_array_when_no_remosaic(self):
        raw = self.caps.split("static Size rawSize(", 1)[1].split("/** Pixel mode needed", 1)[0]
        self.assertIn("if (high && best == null)", raw)
        self.assertIn("best = rawSize(cm, lensId, false);", raw)

    def test_raw_zoom_switches_physical_lenses(self):
        apply_zoom = self.controller.split("private fun applyZoom(", 1)[1].split("private fun selectLens(", 1)[0]
        self.assertIn("if (targetLens != currentLens)", apply_zoom)
        self.assertIn("selectLens(targetLens)", apply_zoom)

    def test_capture_format_options_exposed(self):
        settings = (SRC / "ui/components/CameraSettings.kt").read_text()
        self.assertIn('title = "Capture format"', settings)
        self.assertIn('options = listOf("JPEG", "RAW (DNG)", "YUV")', settings)
        self.assertIn("onCaptureFormatSelected", self.controller)
        self.assertIn("receiveCameraYuv", self.controller)
        self.assertIn("saveCameraYuv", self.controller)


if __name__ == "__main__":
    unittest.main()
