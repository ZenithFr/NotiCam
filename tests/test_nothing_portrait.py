"""Source integration contracts plus native geometry behavior, not device quality tests."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'

class NothingPortrait(unittest.TestCase):
    @unittest.skipUnless(shutil.which('g++'), 'g++ unavailable')
    def test_native_geometry_and_empty_mask_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = str(Path(directory) / 'portrait')
            subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                            str(ROOT / 'tests/portrait_geometry_test.cpp'), '-o', executable], check=True)
            subprocess.run([executable], check=True)

    def test_depth_alignment_is_once_in_both_staging_paths(self):
        native = (ROOT / 'jni/src/VulkanPipeline.cpp').read_text()
        self.assertEqual(2, native.count('portrait::depthRowForColor('))
        self.assertGreaterEqual(native.count('portrait::matteRowForDepth('), 4)
        self.assertIn('portrait::foregroundAtDepth(z_m, s1Still)', native)
        self.assertIn('holdSec < 3.0 && hasRealAlpha', native)
        self.assertIn('validDepths.begin() + validDepths.size() / 10', native)
        shader = (ROOT / 'jni/shaders/alpha.comp').read_text()
        self.assertIn('vec2 depthUv = uv;', shader)
        self.assertNotIn('1.1667', shader)
        self.assertIn('texture(uDepth, q).r', shader)

    def test_refinement_preserves_soft_edges_without_brightness_alpha_lift(self):
        shader = (ROOT / 'jni/shaders/alpha.comp').read_text()
        self.assertIn('1.0 / vec2(256.0, 256.0)', shader)
        self.assertIn('dot(delta, delta)', shader)
        self.assertNotIn('alpha + (luma', shader)
        self.assertNotIn('low-light recovery', shader)
        native = (ROOT / 'jni/src/VulkanPipeline.cpp').read_text()
        self.assertIn('uint32_t curEdgeRefine = 0u;', native)
        self.assertIn('portrait::hasForeground(alphaMatte', native)

    def test_mask_freshness_and_slow_live_shutdown_are_not_bypassed(self):
        controller = (SRC / 'CameraController.kt').read_text()
        gate = controller.split('private fun matteFor(', 1)[1].split('private var shutterClickNs', 1)[0]
        self.assertIn('.forFrame(timestamp, frameGeneration, bokehModelGeneration)', gate)
        self.assertNotIn('latestValidMatte', controller)
        self.assertIn('!liveBokehSuspended', controller)
        self.assertIn('if (liveBokehSuspended) publishedMatte.set(null)', controller)
        self.assertIn('BokehMask.hasForeground(it) || FrameData.hasUsableDepth(depthFor(image.timestamp))', controller)

    def test_discovered_nnapi_devices_and_honest_fallback(self):
        native = (ROOT / 'jni/src/NativeBokehBridge.cpp').read_text()
        for api in ('ANeuralNetworks_getDeviceCount', 'ANeuralNetworksDevice_getType', 'ANEURALNETWORKS_DEVICE_ACCELERATOR'):
            self.assertIn(api, native)
        engines = (SRC / 'BokehEngines.kt').read_text()
        for required in ('NnapiDevices.available()', 'setUseNnapiCpu(false)', 'placement unverified', 'CPU/XNNPACK fallback', 'setAcceleratorName(device)'):
            self.assertIn(required, engines)
        self.assertNotIn('mtk-neuron', engines)
        self.assertIn('-lneuralnetworks', (ROOT / 'jni/Android.mk').read_text())

    def test_neutral_theme_has_no_proprietary_font_or_fake_affiliation(self):
        theme = (SRC / 'ui/theme/Theme.kt').read_text()
        for token in ('0xFFC8102E', '0xFFDCD7D2', '0xFFB1B3B3', 'FontFamily.Monospace'):
            self.assertIn(token, theme)
        self.assertFalse((ROOT / 'res/font').exists())
        screen = (SRC / 'ui/CameraScreen.kt').read_text()
        self.assertIn('fontScale.coerceAtLeast(1f)', screen)
        self.assertIn('clip(RoundedCornerShape(24.dp))', screen)
        controls = (SRC / 'ui/components/Controls.kt').read_text()
        pill = controls.split('fun TogglePill(', 1)[1].split('/** 52 dp gallery', 1)[0]
        self.assertIn('heightIn(min = CameraSpacing.capsuleHeight)', pill)
        self.assertIn('padding(horizontal = 12.dp, vertical = 12.dp)', pill)
        self.assertIn('maxLines = 2', pill)
        self.assertIn('overflow = TextOverflow.Ellipsis', pill)
        self.assertIn('width(80.dp).heightIn(min = CameraSpacing.touch)', controls)
