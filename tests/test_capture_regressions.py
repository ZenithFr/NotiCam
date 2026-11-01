"""Source-contract smoke tests, not substitutes for Android/HAL tests.

These catch accidental restoration of the audited unsafe paths in a bare
checkout. Buffer packing and timestamp matching also have executable JVM tests
in tests/kotlin (run `gradle testDebugUnitTest` with an Android toolchain).
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text()


class CaptureContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.controller = read('src/com/zenith/auxcam/CameraController.kt')
        cls.native = read('jni/src/VulkanPipeline.cpp')
        cls.service = read('src/com/zenith/auxcam/CameraUserService.java')

    def test_sharp_modes_explicitly_bypass_bokeh(self):
        self.assertIn('if (state.bokehActive)', self.controller)
        self.assertIn('if (frozen.bokehActive)', self.controller)
        shader = read('jni/shaders/composite.comp')
        self.assertIn('(pc.rotate90 & 2) != 0', shader)
        self.assertIn('vec4(sharpRgba.rgb, 1.0)', shader)

    def test_only_photo_video_modes_and_explicit_portrait_button(self):
        state = read('src/com/zenith/auxcam/CameraUiState.kt')
        self.assertNotIn('Portrait("Portrait")', state)
        self.assertNotIn('Pro("Pro")', state)
        self.assertIn('Video("Video")', state)
        self.assertIn('mode == CaptureMode.Photo && sessionReady && !captureBusy', state)
        screen = read('src/com/zenith/auxcam/ui/CameraScreen.kt')
        self.assertIn('icon = NotiIcons.Portrait', screen)
        self.assertIn('onClick = actions::onBokehToggle', screen)
        self.assertIn('Video recording is coming later', screen)

    def test_50mp_uses_actual_jpeg_capabilities_and_pixel_mode(self):
        self.assertIn('getHighResolutionJpegSize()', self.service)
        self.assertIn('map.getHighResolutionOutputSizes(ImageFormat.JPEG)', self.service)
        self.assertIn('SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION', self.service)
        self.assertIn('stillConfig.addSensorPixelModeUsed(mStillPixelMode)', self.service)
        self.assertIn('CaptureRequest.SENSOR_PIXEL_MODE, mStillPixelMode', self.service)
        self.assertIn('CaptureRequest.JPEG_QUALITY, (byte) 100', self.service)
        self.assertIn('SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION', self.service)
        self.assertIn('applyCropAndExposure(stillBuilder, mStillFormat == ImageFormat.JPEG)', self.service)
        self.assertNotIn('Resolution.High -> 8160', read('src/com/zenith/auxcam/DeviceProfile.kt'))

    def test_50mp_preserves_encoded_pixels_without_full_bitmap_decode(self):
        jpeg_path = self.controller.split('private fun saveHighResolutionJpeg(', 1)[1].split('private fun buildJpegThumbnail', 1)[0]
        self.assertIn('inJustDecodeBounds = true', jpeg_path)
        self.assertIn('CaptureOutput.matches(bounds.outWidth', jpeg_path)
        self.assertIn('output.write(jpeg)', jpeg_path)
        self.assertNotIn('Bitmap.createBitmap', jpeg_path)
        self.assertNotIn('bitmap.compress(', jpeg_path)
        self.assertIn('if (!published) uri?.let', jpeg_path)

    def test_50mp_rolls_back_and_does_not_apply_bokeh_or_zoom(self):
        self.assertIn('50 MP unavailable for this stream combination', self.controller)
        self.assertIn('Zoom uses 12 MP. Switching', self.controller)
        self.assertIn('Portrait effect uses 12 MP. Switching', self.controller)
        self.assertIn('token == sessionToken', self.controller)

    def test_zero_blur_does_not_restore_default_radius(self):
        shader = read('jni/shaders/coc.comp')
        self.assertIn('max(pc.targetFarFieldPx, 0.0)', shader)
        self.assertNotIn('? pc.targetFarFieldPx : 15.0', shader)

    def test_preview_and_still_share_blur_reference(self):
        constants = re.findall(r'CocPushConstants pcCoc\{([^}]+)\}', self.native)
        self.assertEqual(2, len(constants))
        for constant in constants:
            self.assertIn('targetFarFieldPx, 720.0f', constant)
        # A 15-px radius at 720w is the same relative blur at both pass widths.
        self.assertAlmostEqual((15 / 720 * 1020) / 1020, (15 / 720 * 720) / 720)

    def test_no_synthetic_oval_or_specular_boost(self):
        self.assertNotIn('float normDist = dist / radiusFg;', self.native)
        self.assertNotIn('32, 1.2f', self.native)
        self.assertNotIn('actualTaps = min(pc.numTaps, 16u)', read('jni/shaders/gather.comp'))

    def test_no_fake_raw_control(self):
        controls = read('src/com/zenith/auxcam/ui/components/Controls.kt')
        self.assertNotIn('RawBadge(', controls)
        self.assertNotIn('dumpRawPlanes(', self.controller)

    def test_no_high_quality_denoise_or_sharpening_request(self):
        self.assertNotIn('CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY', self.service)
        self.assertNotIn('CaptureRequest.EDGE_MODE_HIGH_QUALITY', self.service)
        self.assertIn('supports(mNoiseModes, CaptureRequest.NOISE_REDUCTION_MODE_OFF)', self.service)
        self.assertIn('supports(mEdgeModes, CaptureRequest.EDGE_MODE_OFF)', self.service)

    def test_no_double_zoom_or_implicit_lens_switch(self):
        self.assertNotIn('b.set(CaptureRequest.CONTROL_ZOOM_RATIO', self.service)
        zoom = self.service.split('public void setZoom(', 1)[1].split('public void focusAt(', 1)[0]
        self.assertNotIn('restartWithCamera', zoom)

    def test_save_is_transactional(self):
        self.assertIn('check(bitmap.compress(', self.controller)
        self.assertIn('if (!saved) destUri?.let', self.controller)
        self.assertIn('activity.contentResolver.delete(uri', self.controller)
        self.assertNotIn('/sdcard/Pictures/NotiCam/edge_gated.png', self.controller)
        completion = self.controller.split('private fun onStillCaptured()', 1)[1].split('private fun onStats', 1)[0]
        self.assertNotIn('Saved to Photos', completion)

    def test_capture_freezes_actual_image_dimensions(self):
        self.assertIn('val frozenW = image.width', self.controller)
        self.assertIn('val frozenH = image.height', self.controller)
        self.assertIn('check(processed)', self.controller)
        self.assertIn('finally { buffer.close() }', self.controller)
        self.assertIn('captureDesc.width != mFullW', self.native)

    def test_busy_gate_and_timer_cancellation(self):
        controls = read('src/com/zenith/auxcam/ui/components/Controls.kt')
        self.assertIn('enabled = state.canTakePhoto', controls)
        self.assertIn('main.removeCallbacks(timerTick)', self.controller)
        self.assertNotIn('postDelayed({ stillCaptureInProgress.set(false) }', self.controller)

    def test_native_lifetime_and_array_guards(self):
        bridge = read('jni/src/NativeBokehBridge.cpp')
        self.assertIn('std::try_to_lock', bridge)
        self.assertIn('static std::mutex gPipelineMutex', bridge)
        self.assertIn('env->GetArrayLength(data)', bridge)
        self.assertIn('if (n < 0 && errno == EINTR) continue;', self.native)
        self.assertIn('return outputOk;', self.native)

    def test_shader_refresh_is_not_length_based(self):
        activity = read('src/com/zenith/auxcam/MainActivity.kt')
        self.assertNotIn('assetLen', activity)
        self.assertIn('temporary.renameTo(outFile)', activity)

    def test_depth_pairing_and_stride_reader_are_used(self):
        self.assertIn('FrameData.packPlane(plane.buffer', self.controller)
        self.assertEqual(2, self.controller.count('depthFor(image.timestamp),'))
        self.assertIn('generation == frameGeneration', self.controller)

    def test_segmentation_uses_upright_zero_to_one_input(self):
        segmenter = read('src/com/zenith/auxcam/DualMatteSegmenter.kt')
        self.assertIn('outBuf.putFloat(r / 255.0f)', segmenter)
        self.assertNotIn('r / 127.5f', segmenter)
        self.assertIn('FrameData.sensorMatteIndex(', segmenter)
        self.assertIn('val isDual = (preparedMode == TierMode.DUAL)', segmenter)

    def test_stale_session_callbacks_are_rejected(self):
        self.assertGreaterEqual(self.service.count('mGeneration != mService.mSessionGeneration'), 5)


if __name__ == '__main__':
    unittest.main()
