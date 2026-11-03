"""Reference styling and Shizuku discovery contracts; not Android/device tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'


class ReferenceAndPrivilege(unittest.TestCase):
    def test_white_selection_is_separate_from_functional_focus_color(self):
        theme = (SRC / 'ui/theme/Theme.kt').read_text()
        controls = (SRC / 'ui/components/Controls.kt').read_text()
        self.assertIn('val Accent = Color.White', theme)
        self.assertIn('primary = CameraColors.Accent', theme)
        self.assertIn('if (selected) CameraColors.Accent else CameraColors.ControlSurface', controls)
        # A white selection takes dark (OnLight) text so it stays readable.
        self.assertIn('if (selected) CameraColors.OnLight else CameraColors.LabelSecondary', controls)
        self.assertIn('role = Role.Button', controls)
        self.assertIn('selected = active', controls)
        self.assertNotIn('text = label.uppercase()', controls)
        self.assertIn('CameraColors.Yellow', (SRC / 'ui/components/Viewfinder.kt').read_text())

    def test_matte_atmosphere_is_neutral_and_does_not_read_camera_or_thumbnails(self):
        effect = (SRC / 'ui/effects/SceneAtmosphere.kt').read_text()
        self.assertIn('Color.White.copy(alpha = 0.055f)', effect)
        for forbidden in ('produceState', 'Bitmap', 'ImageReader', 'CaptureRequest', 'RenderEffect', '.blur('):
            self.assertNotIn(forbidden, effect)
        self.assertIn('SceneAtmosphere(Modifier.matchParentSize())', (SRC / 'ui/CameraScreen.kt').read_text())

    def test_viewer_keeps_original_fit_and_neutral_surround(self):
        viewer = (SRC / 'ui/GalleryScreen.kt').read_text()
        self.assertIn('SceneAtmosphere(Modifier.matchParentSize())', viewer)
        self.assertNotIn('val backdrop', viewer)
        self.assertIn('ContentScale.Fit', viewer)
        self.assertIn('label = "filmstrip-selection"', viewer)
        self.assertIn('cameraGlass(CircleShape)', viewer)
        self.assertNotIn('.matte(', viewer)

    def test_capture_and_every_capability_query_share_one_privileged_manager(self):
        service = (SRC / 'CameraUserService.java').read_text()
        self.assertIn('private synchronized CameraManager cameraManager()', service)
        self.assertIn('if (mPrivilegedCameraManager != null) return mPrivilegedCameraManager', service)
        self.assertIn('createPackageContext("com.android.shell"', service)
        self.assertNotIn('mContext.getSystemService(Context.CAMERA_SERVICE)', service)
        for method in ('public int[] getHighResolutionJpegSize()', 'public int[] getStandardJpegSize(String lensId)',
                       'public int[] getVideoSize(String lensId)', 'public String getCameraDiagnostics()'):
            body = service.split(method + ' {', 1)[1].split('\n    }', 1)[0]
            self.assertIn('CameraManager cm = cameraManager()', body)
        capture = service.split('private void openSessionOnCameraThread(', 1)[1].split('public int[] getHighResolution', 1)[0]
        self.assertIn('CameraManager cm = cameraManager()', capture)
        self.assertIn('cm.openCamera(mCurrentCameraId', capture)

    def test_report_describes_actual_privilege_not_an_invented_unlock(self):
        service = (SRC / 'CameraUserService.java').read_text()
        for required in ('Process UID:', 'android.os.Process.myUid()', 'getAttributionSource()',
                         'CAMERA permission:', 'SYSTEM_CAMERA permission:', 'attribution does not change this'):
            self.assertIn(required, service)
        for forbidden in ('appops set', 'pm grant', 'setprop ', 'setSensorPrivacy(', 'Runtime.getRuntime().exec'):
            self.assertNotIn(forbidden, service)

    def test_known_auxiliary_ids_are_read_only_not_blind_guesses(self):
        caps = (SRC / 'CameraCapabilities.java').read_text()
        self.assertIn('Arrays.asList("2", "3", "5")', caps)
        self.assertIn('ids.addAll(Arrays.asList(cm.getCameraIdList()))', caps)
        self.assertIn('Enumerated IDs:', caps)
        self.assertIn('Queried IDs (includes existing auxiliary routes):', caps)
        self.assertIn('Metadata eligibility is not proof of a successful capture session.', caps)
        self.assertIn('150_000', caps)
        self.assertNotIn('new CaptureRequest.Key', caps)

    def test_browser_is_a_monochrome_reference_companion_not_a_fake_unlock(self):
        preview = (ROOT / 'design/index.html').read_text()
        for required in ('NOTHING-INSPIRED', '--scene-tone:', '--accent: #fff;',
                         'cameraSubtitle', 'Neutral matte glass',
                         'Sample · Not a camera capture', 'max-width: 340px'):
            self.assertIn(required, preview)
        self.assertIn('simulations', preview)
        self.assertIn('no file was recorded', preview)


if __name__ == '__main__':
    unittest.main()
