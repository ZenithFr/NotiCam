"""Wiring contracts, not Camera2/HAL execution or Compose validation."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'


class SensorControls(unittest.TestCase):
    def test_new_ipc_appends_ids_and_restarts_old_sidecar(self):
        aidl = (SRC / 'IAuxCamService.aidl').read_text()
        self.assertIn('getSensorControlCapabilities() = 17;', aidl)
        self.assertIn('int awbMode) = 18;', aidl)
        self.assertIn('destroy() = 16777114;', aidl)
        controller = (SRC / 'CameraController.kt').read_text()
        self.assertIn('.version(12)', controller)
        callback = (SRC / 'IAuxCamCallback.aidl').read_text()
        self.assertIn('onSensorControlsResult(int token, in long[] selection, String error)', callback)
        self.assertIn('request == sensorControlToken', controller)
        self.assertIn('token == sessionToken && request == sensorControlToken', controller)

    def test_capabilities_require_metadata_and_request_keys(self):
        service = (SRC / 'CameraUserService.java').read_text()
        caps = service.split('private static SensorControlCapabilities readSensorControlCapabilities', 1)[1].split('@Override', 1)[0]
        for key in ('REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR', 'CONTROL_AE_MODE_OFF',
                    'SENSOR_INFO_SENSITIVITY_RANGE', 'SENSOR_INFO_EXPOSURE_TIME_RANGE',
                    'SENSOR_INFO_MAX_FRAME_DURATION', 'getAvailableCaptureRequestKeys',
                    'keys.contains(CaptureRequest.SENSOR_FRAME_DURATION)', 'CONTROL_AWB_AVAILABLE_MODES'):
            self.assertIn(key, caps)
        self.assertNotIn('getHighResolutionJpegSize', caps)
        self.assertNotIn('mediatek', caps.lower())

    def test_manual_keys_follow_flash_and_auto_controls_in_both_paths(self):
        service = (SRC / 'CameraUserService.java').read_text()
        preview = service.split('private void applyPreviewControls', 1)[1].split('private void applyStillFlash', 1)[0]
        self.assertGreater(preview.index('applySensorControls(b, false)'), preview.index('CONTROL_AE_MODE_ON'))
        capture = service.split('private void captureStillOnCameraThread', 1)[1].split('mCaptureSession.capture', 1)[0]
        self.assertLess(capture.index('applyStillFlash(stillBuilder)'), capture.index('applySensorControls(stillBuilder, true)'))
        self.assertIn('enableZsl && !mManualExposureEnabled', capture)
        manual = service.split('private void applySensorControls', 1)[1].split('@Override', 1)[0]
        for key in ('CONTROL_AE_MODE_OFF', 'SENSOR_SENSITIVITY', 'SENSOR_EXPOSURE_TIME', 'SENSOR_FRAME_DURATION',
                    'SensorControlPolicy.liveExposure', 'SensorControlPolicy.exposure', 'SensorControlPolicy.frameDuration'):
            self.assertIn(key, manual)
        for key in ('SENSOR_SENSITIVITY', 'SENSOR_EXPOSURE_TIME', 'SENSOR_FRAME_DURATION'):
            self.assertIn('b.set(CaptureRequest.' + key + ', null)', manual)
        self.assertNotIn('!mHighResolutionStill', manual)

    def test_failures_restore_last_selection_and_slider_backlog_is_coalesced(self):
        service = (SRC / 'CameraUserService.java').read_text()
        setter = service.split('public void setSensorControls', 1)[1].split('/** Re-submits', 1)[0]
        self.assertIn('mCameraHandler.post', setter)
        self.assertIn('token != mLatestSensorControlToken.get()', setter)
        self.assertIn('mManualExposureEnabled = oldManual', setter)
        self.assertIn('mManualExposureNs = oldExposure', setter)
        self.assertIn('callback.onSensorControlsResult(token', setter)
        self.assertNotIn('notifyError(', setter)
        self.assertNotIn('closeSession', setter)

    def test_ui_real_controls_preserve_auto_and_capture_quality(self):
        screen = (SRC / 'ui/components/CaptureControlRail.kt').read_text()
        for action in ('onManualExposureToggle', 'onSensorIsoChanged', 'onSensorShutterChanged', 'onWhiteBalanceTemperatureChanged'):
            self.assertIn(action, screen)
        self.assertIn('supportsTemperature', screen)
        self.assertIn('state.canAdjustSensorControls', screen)
        self.assertNotIn('SensorControlSettings(state, actions)', (SRC / 'ui/components/CameraSettings.kt').read_text())
        controller = (SRC / 'CameraController.kt').read_text()
        sensor = controller.split('private fun refreshSensorControlCapabilities', 1)[1].split('private fun refreshHighResolutionCapability', 1)[0]
        for forbidden in ('rebuildStillSession(', 'Bitmap.', 'native.', 'bokehEnabled ='):
            self.assertNotIn(forbidden, sensor)
        self.assertNotRegex(sensor, r'\bresolution\s*=(?!=)')
        flash = controller.split('override fun onFlashClick()', 1)[1].split('private fun flashLabel', 1)[0]
        self.assertIn('if (state.manualExposure)', flash)
        self.assertIn('const val CAPTURE_JPEG_QUALITY = 100', controller)

    def test_guides_never_enter_capture_and_preferences_use_stable_ids(self):
        controller = (SRC / 'CameraController.kt').read_text()
        self.assertIn('putString("guide", guide.id)', controller)
        self.assertIn('FramingGuide.fromId', controller)
        view = (SRC / 'ui/components/Viewfinder.kt').read_text()
        self.assertIn('framingGuide.lines(size.width, size.height)', view)
        self.assertIn('framingGuide = state.framingGuide', (SRC / 'ui/CameraScreen.kt').read_text())
        self.assertNotIn('FramingGuide', (SRC / 'CameraUserService.java').read_text())
        modes = (SRC / 'CameraUiState.kt').read_text().split('enum class CaptureMode', 1)[1].split('/** Flash', 1)[0]
        self.assertEqual(['Video', 'Photo'], re.findall(r'\b(\w+)\("', modes))


if __name__ == '__main__':
    unittest.main()
