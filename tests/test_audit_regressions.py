"""Cross-layer source contracts found by the main-branch audit, not device tests."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'
ANDROID = '{http://schemas.android.com/apk/res/android}'

class AuditRegressions(unittest.TestCase):
    def test_shizuku_binder_is_not_registered_as_android_service(self):
        manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
        names = [item.get(ANDROID + 'name') for item in manifest.findall('./application/service')]
        self.assertNotIn('.CameraUserService', names)
        self.assertIn('extends IAuxCamService.Stub', (SRC / 'CameraUserService.java').read_text())
        controller = (SRC / 'CameraController.kt').read_text()
        self.assertIn('Shizuku.UserServiceArgs(', controller)
        self.assertIn('ComponentName(activity.packageName, CameraUserService::class.java.name)', controller)

    def test_raw_sharing_uses_the_container_mime_policy(self):
        gallery = (SRC / 'ui/GalleryScreen.kt').read_text()
        self.assertIn('type = CaptureOutput.shareMimeType(current.video, current.name)', gallery)

    def test_recorded_profile_is_checked_before_publishing(self):
        video = (SRC / 'VideoCapture.kt').read_text().split('fun finish(save:', 1)[1]
        self.assertIn('METADATA_KEY_CAPTURE_FRAMERATE', video)
        self.assertLess(video.index('VideoPolicy.matchesRecordedOutput('), video.index('IS_PENDING, 0'))
        self.assertIn('contentResolver.delete(destination', video)

    def test_haptic_fallback_declares_normal_vibration_permission(self):
        manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
        permissions = [item.get(ANDROID + 'name') for item in manifest.findall('uses-permission')]
        self.assertIn('android.permission.VIBRATE', permissions)

    def test_depth_reader_uses_the_platform_y8_format_symbol(self):
        controller = (SRC / 'CameraController.kt').read_text()
        reader = controller.split('depthReader = ImageReader.newInstance(', 1)[1].split(').also', 1)[0]
        self.assertIn('ImageFormat.Y8', reader)

    def test_camera_open_has_explicit_denial_handling(self):
        for name in ('CameraUserService.java', 'probe/DepthProbeService.java'):
            source = (SRC / name).read_text()
            self.assertRegex(source, r'catch \(SecurityException (?:denied|e)\)')
