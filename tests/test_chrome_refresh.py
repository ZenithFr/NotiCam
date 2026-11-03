"""Layout/wiring contracts, not rendered UI or device-capture assertions."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'

class ChromeRefresh(unittest.TestCase):
    def text(self, path): return (SRC / path).read_text()

    def test_top_left_is_raw_not_title_and_mode_switch_is_labeled(self):
        controls = self.text('ui/components/Controls.kt')
        top = controls.split('fun TopBar(', 1)[1].split('fun ModeSwitch(', 1)[0]
        self.assertNotIn('Text("NotiCam"', top)
        self.assertNotIn('state.status', top)
        self.assertIn('RawCaptureButton(state, actions)', top)
        bottom = controls.split('fun BottomBar(', 1)[1]
        self.assertNotIn('ModeCarousel(', controls)
        self.assertIn('ModeSwitch(state, actions', bottom)
        self.assertIn('!state.videoRecording && !state.videoStarting', controls)

    def test_badge_timeout_does_not_hide_live_recording_duration(self):
        screen = self.text('ui/CameraScreen.kt')
        self.assertIn('persistent = state.videoRecording', screen)
        self.assertIn('LaunchedEffect(label, persistent)', screen)
        self.assertIn('if (!persistent) { kotlinx.coroutines.delay(2000); visible = false }', screen)

    def test_raw_hold_releases_and_cancels_without_double_toggle(self):
        raw = self.text('ui/components/RawCaptureButton.kt')
        for contract in ('tween(1000, easing = LinearEasing)', 'drawArc(', 'hold.cancelAndJoin()',
                         'released && !revealed', 'onDismissRequest', 'horizontalScroll',
                         'onLongClick("Choose RAW resolution and frames")'):
            self.assertIn(contract, raw)
        self.assertIn('if (!enabled) { expanded = false; progress.snapTo(0f) }', raw)

    def test_manual_settings_removed_but_rail_is_lower_and_snaps(self):
        self.assertNotIn('Manual(', self.text('ui/components/ControlSection.kt'))
        self.assertNotIn('SensorControlSettings', self.text('ui/components/CameraSettings.kt'))
        rail = self.text('ui/components/CaptureControlRail.kt')
        for contract in ('top = 5.dp', '"SHUTTER"', 'detents = SensorControlPolicy.isoStops', 'detents = SensorControlPolicy.shutterStops', 'onWhiteBalanceTemperatureChanged'):
            self.assertIn(contract, rail)
        self.assertNotIn('WhiteBalancePreset', rail)
        self.assertNotIn('"TIME"', rail)

    def test_real_temperature_is_gated_and_applied_to_still_not_rgb_filter(self):
        service = self.text('CameraUserService.java')
        for contract in ('REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING',
                         'CONTROL_AWB_MODE_OFF', 'COLOR_CORRECTION_MODE_TRANSFORM_MATRIX',
                         'mAutoColorTransform', 'mAutoColorGains', 'setWhiteBalanceTemperature',
                         'mKelvin = oldKelvin', 'applySensorControls(stillBuilder, true)'):
            self.assertIn(contract, service)
        self.assertNotIn('Bitmap', service.split('public void setWhiteBalanceTemperature', 1)[1].split('/** Re-submits', 1)[0])

    def test_50mp_raw_requires_real_stream_and_payload_budget(self):
        caps = self.text('CameraCapabilities.java').split('static Size rawSize(', 1)[1].split('/** Normal Camera2', 1)[0]
        for contract in ('getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)',
                         'high != (pixels >= 45_000_000L)', 'RawFrameMatcher.maxFramesForSize'):
            self.assertIn(contract, caps)
        controller = self.text('CameraController.kt')
        self.assertIn('getRawConfiguration(currentLens, state.resolution == Resolution.High)', controller)
        self.assertNotIn('resolution == Resolution.Standard && maxManualExposureNs', self.text('CameraUiState.kt'))
        probe = controller.split('private fun refreshHighResolutionCapability', 1)[1].split('private fun onSessionError', 1)[0]
        self.assertIn('getHighResolutionJpegConfiguration(lens)', probe)
        self.assertNotIn('?: 8160', probe)

    def test_translucent_matte_does_not_claim_surfaceview_backdrop_blur_or_fake_hdr(self):
        surface = self.text('ui/effects/ControlSurface.kt')
        self.assertIn('baseColor.copy(alpha = 0.10f)', surface)
        self.assertIn('rememberMatteGrain()', surface)
        self.assertIn('does NOT claim backdrop', surface)
        self.assertIn('HDR merge and Ultra HDR export are not implemented yet', self.text('ui/components/CameraSettings.kt'))
