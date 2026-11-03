"""Video integration contracts; device/HAL/codec behavior still needs real hardware."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'

class VideoProfiles(unittest.TestCase):
    def test_profile_query_checks_actual_dimensions_rate_and_native_field(self):
        source = (SRC / 'CameraCapabilities.java').read_text()
        body = source.split('static Size videoProfile(', 1)[1].split('/** Largest advertised', 1)[0]
        for token in ('VideoPolicy.allowedRate', 'recordingRange(cm, id, fps)', 'VideoPolicy.matchesQuality',
                      'VideoPolicy.matchesSensor', 'duration <= 0', '1_000_000_000L / fps',
                      'isHardwareAccelerated', 'COLOR_FormatSurface', 'areSizeAndRateSupported'):
            self.assertIn(token, body)
        self.assertNotIn('fourKVideoSize', source)

    def test_exact_rate_reaches_recorder_and_camera_without_vendor_overclock(self):
        recorder = (SRC / 'VideoCapture.kt').read_text()
        self.assertIn('r.setVideoFrameRate(targetFps)', recorder)
        self.assertIn('areSizeAndRateSupported(width, height, targetFps.toDouble())', recorder)
        self.assertNotIn('areSizeAndRateSupported(width, height, VideoPolicy.FPS', recorder)
        service = (SRC / 'CameraUserService.java').read_text()
        self.assertIn('mVideoRecordFps = previewMaxFps;', service)
        self.assertIn('CameraCapabilities.recordingRange(cm, mCurrentCameraId, mVideoRecordFps)', service)
        self.assertIn('mPreviewFpsRange = null; // Fail closed', service)
        self.assertNotIn('hfpsMode', service)
        self.assertNotIn('new Range<>(30, 60)', service)

    def test_async_profiles_cannot_cross_lens_session_or_selection(self):
        source = (SRC / 'CameraController.kt').read_text()
        refresh = source.split('private fun refreshVideoProfiles()', 1)[1].split('override fun onVideoQualitySelected', 1)[0]
        for token in ('token == videoProfileToken', 'session == sessionToken', 'currentLens == lens',
                      '!disposed && foreground', 'state.videoQuality == quality', 'state.videoTargetFps == fps'):
            self.assertIn(token, refresh)
        start = source.split('private fun startVideo()', 1)[1].split('private fun stopVideo', 1)[0]
        self.assertIn('val fps = state.videoTargetFps', start)
        self.assertIn('getVideoConfiguration(lens, quality, fps)', start)
        self.assertIn('capture.prepare(size[0], size[1], audio, size[2], fps,', start)
        self.assertNotIn('svc.getVideoSize', start)

    def test_independent_quality_and_rate_controls_and_unsupported_state(self):
        ui = (SRC / 'ui/components/VideoProfileSettings.kt').read_text()
        for token in ('onVideoQualitySelected', 'onVideoRateSelected', 'listOf(24, 30, 60)', 'listOf(24, 30)',
                      'videoProfilesChecked', 'videoSupportedProfiles', 'Unavailable rates are disabled'):
            self.assertIn(token, ui)
        controls = (SRC / 'ui/components/Controls.kt').read_text()
        self.assertIn('actions.onVideoRateSelected', controls)
        self.assertIn('VideoProfileSettings(state, actions)', (SRC / 'ui/components/CameraSettings.kt').read_text())

    def test_new_query_appends_transaction_and_restarts_old_sidecar(self):
        self.assertIn('getVideoConfiguration(String lensId, int quality, int fps) = 22', (SRC / 'IAuxCamService.aidl').read_text())
        self.assertIn('.version(12)', (SRC / 'CameraController.kt').read_text())

    def test_no_synthetic_npu_worker_runs_in_sharp_preview(self):
        self.assertFalse((ROOT / 'jni/src/NpuWorker.cpp').exists())
        self.assertNotIn('mNpuWorker', (ROOT / 'jni/src/VulkanPipeline.cpp').read_text())
        self.assertNotIn('src/NpuWorker.cpp', (ROOT / 'jni/Android.mk').read_text())
