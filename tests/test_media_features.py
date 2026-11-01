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


if __name__ == '__main__':
    unittest.main()
