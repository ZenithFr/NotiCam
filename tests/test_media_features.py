"""Camera/media source contracts, not Android runtime tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'


class MediaFeatures(unittest.TestCase):
    def test_optical_two_times_has_no_settings_gate_or_double_crop(self):
        controller = (SRC / 'CameraController.kt').read_text()
        controls = (SRC / 'ui/components/Controls.kt').read_text()
        zoom = (SRC / 'CameraZoom.java').read_text()
        self.assertIn('CameraZoom.lensForZoom(zoom)', controller)
        self.assertIn('return zoom >= 2f ? "3" : "0"', zoom)
        self.assertIn('global / base', zoom)
        self.assertNotIn('onTelephotoToggle', controller + controls)
        self.assertNotIn('TelephotoPill', controls)
        self.assertNotIn('TelephotoGlyph', controls)
        self.assertIn('CameraZoom.digitalRatio', (SRC / 'CameraUserService.java').read_text())

    def test_sharp_photos_use_native_advertised_jpeg_not_rendered_preview(self):
        controller = (SRC / 'CameraController.kt').read_text()
        self.assertIn('getStandardJpegSize(currentLens)', controller)
        self.assertIn('if (!bokeh) ImageReader.newInstance(width, height, ImageFormat.JPEG', controller)
        self.assertIn('output.write(jpeg)', controller)
        service = (SRC / 'CameraUserService.java').read_text()
        self.assertIn('applyCropAndExposure(stillBuilder, mHighResolutionStill)', service)
        self.assertIn('mZoomRatio = Float.isFinite(requestedZoom)', service)

    def test_50mp_searches_all_openable_main_fov_ids_and_reports_vendor_paths(self):
        caps = (SRC / 'CameraCapabilities.java').read_text()
        self.assertIn('cm.getCameraIdList()', caps)
        self.assertIn('Math.abs(fov / mainFov - 1) > .15', caps)
        self.assertIn('getCameraExtensionCharacteristics', caps)
        self.assertIn('getAvailableSessionKeys()', caps)
        self.assertIn('getAvailableCaptureRequestKeys()', caps)
        self.assertIn('Vendor output tables and extension sizes are diagnostic only', caps)
        self.assertNotIn('new CaptureRequest.Key', caps)

    def test_video_has_actual_encoder_audio_and_transactional_publish(self):
        video = (SRC / 'VideoCapture.kt').read_text()
        for required in ('VideoEncoder.H264', 'AudioEncoder.AAC', 'AudioSource.MIC',
                         'VideoSource.SURFACE', 'IS_PENDING, 1', 'IS_PENDING, 0',
                         'recorder).stop()', 'contentResolver.delete(destination', 'METADATA_KEY_DURATION'):
            self.assertIn(required, video)
        self.assertIn('RECORD_AUDIO', (ROOT / 'AndroidManifest.xml').read_text())
        self.assertIn('onRequestPermissionsResult', (SRC / 'MainActivity.kt').read_text())

    def test_video_session_is_separate_from_still_and_depth(self):
        service = (SRC / 'CameraUserService.java').read_text()
        self.assertIn('if (mVideoMode)', service)
        self.assertIn('outputs.add(new OutputConfiguration(mRecordingSurface))', service)
        self.assertIn('mVideoMode ? CameraDevice.TEMPLATE_RECORD', service)
        self.assertIn('CONTROL_VIDEO_STABILIZATION_MODE_OFF', service)
        controller = (SRC / 'CameraController.kt').read_text()
        self.assertIn('videoGeneration', controller)
        self.assertIn('main.removeCallbacks(videoStartTimeout)', controller)
        self.assertIn('stopVideo(save = state.videoRecording, restore = false)', controller)

    def test_viewer_fits_original_and_owns_only_app_media(self):
        gallery = (SRC / 'ui/GalleryScreen.kt').read_text()
        for required in ('HorizontalPager', 'ContentScale.Fit', 'OWNER_PACKAGE_NAME',
                         'IS_PENDING', 'ImageDecoder', 'setTargetSampleSize(sample)',
                         'FLAG_GRANT_READ_URI_PERMISSION', 'AlertDialog',
                         'contentResolver.delete', 'stopPlayback()', 'calculateZoom()'):
            self.assertIn(required, gallery)
        controller = (SRC / 'CameraController.kt').read_text()
        viewer = controller.split('override fun onGalleryOpen()', 1)[1].split('override fun onCameraReport()', 1)[0]
        self.assertNotIn('Intent.ACTION_VIEW', viewer)
        self.assertIn('galleryOpen = true', viewer)
        self.assertIn('service?.closeSession()', viewer)

    def test_recorder_selects_native_aspect_camera_and_encoder_intersection(self):
        caps = (SRC / 'CameraCapabilities.java').read_text().split('static Size videoSize(', 1)[1].split('private static boolean hasCapability', 1)[0]
        for required in ('SENSOR_INFO_ACTIVE_ARRAY_SIZE', 'VideoPolicy.matchesSensor',
                         'getOutputSizes(MediaRecorder.class)', 'getOutputMinFrameDuration',
                         'CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES', 'isHardwareAccelerated()',
                         'areSizeAndRateSupported', 'COLOR_FormatSurface', 'VideoPolicy.FPS'):
            self.assertIn(required, caps)
        self.assertNotIn('1920', caps)
        self.assertNotIn('16.0 / 9.0', caps)
        video = (SRC / 'VideoCapture.kt').read_text()
        self.assertIn('VideoPolicy.bitrate(width, height)', video)
        self.assertIn('bitrateRange.clamp', video)
        self.assertIn('No substitute.', (SRC / 'CameraController.kt').read_text())
        self.assertNotIn('16:9', (SRC / 'ui/CameraScreen.kt').read_text())

    def test_gallery_uses_rotated_video_metadata_not_hardcoded_portrait_crop(self):
        gallery = (SRC / 'ui/GalleryScreen.kt').read_text()
        for key in ('METADATA_KEY_VIDEO_WIDTH', 'METADATA_KEY_VIDEO_HEIGHT', 'METADATA_KEY_VIDEO_ROTATION',
                    'VideoPolicy.displayAspect(w, h, rotation)', 'minOf(maxWidth, maxHeight * aspect)', 'Modifier.size(videoWidth, videoHeight)'):
            self.assertIn(key, gallery)
        self.assertNotIn('aspectRatio(9f / 16f)', gallery)

    def test_gallery_lifts_actions_above_unconsumed_physical_dialog_insets(self):
        gallery = (SRC / 'ui/GalleryScreen.kt').read_text()
        for key in ('decorFitsSystemWindows = false', 'gallerySafePadding()',
                    'getRootWindowInsets(view)', 'getInsetsIgnoringVisibility',
                    'Type.systemBars()', 'Type.displayCutout()', 'WindowInsets.safeDrawing',
                    'fallback.getBottom(density)', '+ 20.dp', 'removeOnGlobalLayoutListener'):
            self.assertIn(key, gallery)
        self.assertNotIn('setOnApplyWindowInsetsListener', gallery)

    def test_reserved_shizuku_destroy_preserves_original_transaction_ids(self):
        import re
        aidl = (SRC / 'IAuxCamService.aidl').read_text()
        methods = re.findall(r'(?:void|boolean|int\[\]|long\[\]|String)\s+(\w+)\([^;]*?\)\s*=\s*(\d+);', aidl)
        self.assertEqual([int(code) for _, code in methods[:-1]], list(range(26)))
        self.assertEqual(methods[-1], ('destroy', '16777114'))
        controller = (SRC / 'CameraController.kt').read_text()
        self.assertIn('.version(12)', controller)
        self.assertIn('.daemon(false)', controller)
        self.assertIn('.tag("noticam-', controller)
        self.assertIn('exitingService?.destroy()', controller)

    def test_camera_exit_cleans_independently_and_handles_client_death(self):
        service = (SRC / 'CameraUserService.java').read_text()
        for key in ('linkToDeath(mClientDeath', 'unlinkToDeath(mClientDeath',
                    'mClientDeath = this::destroy', 'compareAndSet(false, true)',
                    'CameraExitWatchdog', 'Thread.sleep(2000)', 'postAtFrontOfQueue',
                    'android.os.Process.killProcess(android.os.Process.myPid())',
                    'session.abortCaptures()', 'session.close()', 'camera.close()',
                    'mStillSurface = null', 'surface.release()', 'IAuxCamCallback callback = mCallback'):
            self.assertIn(key, service)
        self.assertNotIn('killall', service)
        close = service.split('private void closeSessionOnCameraThread()', 1)[1].split('private void notifyError', 1)[0]
        self.assertIn('if (camera != null) try { camera.close(); }', close)
        self.assertLess(close.index('mCaptureSession = null'), close.index('session.close()'))

    def test_native_engine_released_on_pause_and_reinitialized_with_surviving_surface(self):
        controller = (SRC / 'CameraController.kt').read_text()
        pause = controller.split('fun pause()', 1)[1].split('fun dispose()', 1)[0]
        self.assertIn('native.destroyEngine()', pause)
        self.assertLess(pause.index('foreground = false'), pause.index('service?.closeSession()'))
        start = controller.split('fun start()', 1)[1].split('fun pause()', 1)[0]
        self.assertIn('initializePreviewEngine(it)', start)
        self.assertIn('initializePreviewEngine(it)', controller.split('override fun onGalleryClose()', 1)[1])

    def test_late_portrait_still_cannot_resurrect_persistent_background_gpu_owner(self):
        native = (ROOT / 'jni/src/NativeBokehBridge.cpp').read_text()
        still = native.split('Java_com_zenith_auxcam_MainActivity_nativeProcessStillBokeh(', 1)[1].split('extern "C"', 1)[0]
        self.assertIn('std::unique_ptr<VulkanPipeline> headlessStillPipeline', still)
        self.assertIn('VulkanPipeline* stillPipeline = gPipeline.get()', still)
        self.assertIn('headlessStillPipeline->init(nullptr', still)
        self.assertIn('stillPipeline->processStillBokeh(', still)
        self.assertNotIn('gPipeline = std::make_unique', still)
        implementation = (ROOT / 'jni/src/VulkanPipeline.cpp').read_text()
        destructor = implementation.split('VulkanPipeline::~VulkanPipeline()', 1)[1].split('bool VulkanPipeline::init', 1)[0]
        self.assertIn('destroy();', destructor)

    def test_sleeker_visuals_keep_accessible_tap_targets(self):
        controls = (SRC / 'ui/components/Controls.kt').read_text()
        button = controls.split('fun MatteIconButton(', 1)[1].split('fun ShutterButton(', 1)[0]
        self.assertIn('size(CameraSpacing.touch)', button)
        self.assertIn('.size(CameraSpacing.iconButton)', button)
        zoom = (SRC / 'ui/components/ZoomChips.kt').read_text()
        self.assertIn('chipSize: Dp = 48.dp', zoom)
        self.assertIn('.padding(8.dp)', zoom)

    def test_raw_hdr_log_diagnostics_do_not_pretend_to_enable_raw_video(self):
        caps = (SRC / 'CameraCapabilities.java').read_text()
        for key in ('REQUEST_AVAILABLE_CAPABILITIES_RAW', 'ImageFormat.RAW10', 'ImageFormat.RAW12',
                    'getOutputMinFrameDuration(format, size)', 'REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES',
                    'DynamicRangeProfiles.HLG10', 'ImageFormat.YCBCR_P010', 'MIMETYPE_VIDEO_HEVC',
                    'not proof of RAW video throughput', 'not camera Log'):
            self.assertIn(key, caps)
        self.assertNotIn('new CaptureRequest.Key', caps)


if __name__ == '__main__':
    unittest.main()
