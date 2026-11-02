"""RAW integration/source contracts, not substitutes for Camera2/device capture tests.
Production timestamp/ownership behavior is exercised separately by RawFrameMatcherTest.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/com/zenith/auxcam"


class RawCaptureContracts(unittest.TestCase):
    def setUp(self):
        self.service = (SRC / "CameraUserService.java").read_text()
        self.controller = (SRC / "CameraController.kt").read_text()
        self.settings = (SRC / "ui/components/CameraSettings.kt").read_text()

    def test_protocol_appends_raw_methods_and_completion_without_reusing_old_ids(self):
        aidl = (SRC / "IAuxCamService.aidl").read_text()
        for name, number in (("getRawSensorSize", 19), ("triggerRawBurst", 20), ("cancelRawCapture", 21)):
            self.assertRegex(aidl, rf"\b{name}\([^;]+\)\s*=\s*{number}\s*;")
        self.assertIn("onRawCaptureFinished", (SRC / "IAuxCamCallback.aidl").read_text())
        self.assertIn("version(9)", self.controller)
        self.assertIn("finishRawCapture(request, savedCount, error)", self.controller)

    def test_raw_capability_requires_both_flag_and_bounded_advertised_stream(self):
        caps = (SRC / "CameraCapabilities.java").read_text().split("static Size rawSize(", 1)[1].split("/** Normal Camera2", 1)[0]
        for required in ("REQUEST_AVAILABLE_CAPABILITIES_RAW", "getOutputSizes(ImageFormat.RAW_SENSOR)", "RawFrameMatcher.maxFramesForSize"):
            self.assertIn(required, caps)
        reader = self.controller.split("private fun createStillReader", 1)[1].split("private fun", 1)[0]
        self.assertIn("getRawSensorSize", reader)
        self.assertIn("checkNotNull(rawSize)[0]", reader)
        self.assertIn("checkNotNull(rawSize)[1]", reader)

    def test_raw_uses_fifo_acquisition_and_exact_per_request_metadata(self):
        raw = self.service.split("private void startRawCapture", 1)[1].split("public void triggerStill(long shutterCommandNs, boolean", 1)[0]
        for required in ("captureBurst(requests", "b.setTag(index)", "CaptureResult.SENSOR_TIMESTAMP", "matcher.result(timestamp", "matcher.image(timestamp", "acquireNextImage()"):
            self.assertIn(required, raw)
        for forbidden in ("acquireLatestImage", "mLastCaptureResult", "mLastRawImage", "maybeWriteDngLocked"):
            self.assertNotIn(forbidden, self.service)
        self.assertIn("RawFrameMatcher.exifOrientation(mSensorOrientation)", raw)

    def test_writer_and_reader_ownership_are_separate_from_camera_handler(self):
        self.assertIn('new Thread(r, "RawDngWriter")', self.service)
        self.assertIn("job.outputs[pair.index] = null", self.service)
        self.assertIn("mRawWriter.execute(write)", self.service)
        self.assertIn("RejectedExecutionException", self.service)
        self.assertIn("pair.image.close()", self.service)
        self.assertIn("finally { if (!transferred)", self.service)
        self.assertIn("mRawWriter.execute(() -> { try { retired.close();", self.service)
        self.assertNotIn("dng.writeImage", self.service.split("private void onRawImageAvailable", 1)[0])

    def test_completion_is_write_backed_and_failure_has_dedicated_cleanup(self):
        raw = self.service.split("private void writeMatchedRaw", 1)[1].split("public void triggerStill(long shutterCommandNs, boolean", 1)[0]
        for required in ("stream.flush()", "if (error != null)", "++job.saved == job.outputs.length", "rawFinished(job.owner, job.token, job.saved, null)", "job.cancelled.set(true)", "job.matcher.close()", "closeRawOutputs(job.outputs)", "abortCaptures()"):
            self.assertIn(required, raw)
        self.assertIn("20_000L", self.service)

    def test_preparation_dispatch_cannot_overtake_main_thread_cancellation(self):
        prepare = self.controller.split("private fun beginRawCapture", 1)[1].split("private fun finishRawCapture", 1)[0]
        dispatch = prepare.split("// Dispatch and cancellation share Main", 1)[1]
        for required in ("post {", "job.destinations.getAndSet(null)", "pendingRawCapture === job", "job.generation == sessionToken", "svc.triggerRawBurst(job.token, commandNs, destinations)", "finally { for (fd in destinations)"):
            self.assertIn(required, dispatch)
        self.assertLess(dispatch.index("post {"), dispatch.index("svc.triggerRawBurst"))
        cleanup = self.controller.split("private fun deleteRawAssets", 1)[1].split("private fun beginRawCapture", 1)[0]
        self.assertIn("job.destinations.getAndSet(null)", cleanup)
        self.assertIn("job.cancelled.set(true)", cleanup)
        self.assertIn("io.execute { deleteRawAssets(job) }", cleanup)
        self.assertIn("service?.cancelRawCapture(job.token)", cleanup)

    def test_pending_outputs_and_visible_success_use_same_sequence_identity(self):
        save = self.controller.split("private fun finishRawCapture", 1)[1].split("private fun onStats", 1)[0]
        for required in ("job.finalizing", "savedCount != job.count", "pfd.statSize > 0", "IS_PENDING, 0", "deleteRawAssets(job)"):
            self.assertIn(required, save)
        self.assertIn("IS_PENDING, 1", self.controller.split("private fun beginRawCapture", 1)[1].split("private fun finishRawCapture", 1)[0])
        success = save.split("post { if (pendingRawCapture === job", 1)[1]
        self.assertIn("job.generation == sessionToken", success)
        self.assertIn("latestPhotoUri = savedUri", success)
        self.assertIn("pendingRawCapture = null", success)
        self.assertNotIn("latestPhotoUri =", save.split("post { if (pendingRawCapture === job", 1)[0])

    def test_raw_ui_exposes_only_supported_counts_and_truthful_sequence_semantics(self):
        for required in ("1..state.maxRawFrames", "actions.onRawFramesSelected(count)", "state.rawCapabilityChecked", "state.supportsRaw", "No alignment, merging or HDR", "Single frame remains the default"):
            self.assertIn(required, self.settings)
        self.assertNotIn("16-bit DNG", self.settings)
        screen = (SRC / "ui/CameraScreen.kt").read_text()
        self.assertIn("state.raw && state.captureBusy", screen)
        self.assertIn("actions::onRawCaptureCancel", screen)
        self.assertIn("Box(Modifier.fillMaxWidth().height(80.dp)", screen)

    def test_searchable_sections_retain_existing_controls(self):
        sections = (SRC / "ui/components/ControlSection.kt").read_text()
        for label in ("Capture", "Manual", "Portrait", "Aids", "System"):
            self.assertIn(f'{label}("{label}"', sections)
        for required in ("BasicTextField", "Search camera controls", "SensorControlSettings(state, actions)", "BokehModelSettings(state, actions)", "actions::onCameraReport", "actions::onFramingGuideCycle", "actions::onTimerCycle"):
            self.assertIn(required, self.settings)
        self.assertNotIn(".border(", self.settings)

    def test_instrument_rail_commands_are_real_and_not_interpolated_requests(self):
        rail = (SRC / "ui/components/CaptureControlRail.kt").read_text()
        for action in ("onManualExposureToggle", "onSensorIsoChanged", "onSensorShutterChanged", "onWhiteBalanceSelected", "onManualFocusChanged", "onFocusReset", "onEvChanged"):
            self.assertIn(action, rail)
        self.assertIn("state.canAdjustSensorControls", rail)
        self.assertIn("supportsWhiteBalance(it.mode)", rail)
        self.assertNotIn(".border(", rail)
        self.assertNotIn("onValueChange = { actions.onSensor", rail)


if __name__ == "__main__":
    unittest.main()
