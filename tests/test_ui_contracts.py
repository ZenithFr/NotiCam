"""Structural UI regressions; these do not compile or exercise Android UI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'src/com/zenith/auxcam/ui'


class UiContracts(unittest.TestCase):
    def test_glass_surface_retains_body_without_decorative_rim(self):
        surface = (UI / 'effects/ControlSurface.kt').read_text()
        self.assertIn('background(baseColor, shape)', surface)
        self.assertIn('Brush.linearGradient', surface)
        for forbidden in ('.border(', 'drawRoundRect', 'blur('):
            self.assertNotIn(forbidden, surface)
        self.assertTrue(any('cameraGlass' in p.read_text() for p in UI.rglob('*.kt')))

    def test_rear_lens_cycle_button_removed_not_zoom_selection(self):
        controller = (ROOT / 'src/com/zenith/auxcam/CameraController.kt').read_text()
        controls = (UI / 'components/Controls.kt').read_text()
        self.assertNotIn('onSwitchLens', controller + controls)
        self.assertIn('Spacer(Modifier.size(CameraSpacing.gallery))', controls)
        self.assertIn('override fun onZoomSelected', controller)
        self.assertIn('ZoomChips(zoom = state.zoom', (UI / 'CameraScreen.kt').read_text())

    def test_zoom_animation_is_presentation_only(self):
        chips = (UI / 'components/ZoomChips.kt').read_text()
        self.assertIn('kotlin.math.ln(', chips)
        self.assertIn('targetValue = (chipSize + gap) * position', chips)
        self.assertIn('animationSpec = CameraMotion.fluid()', chips)
        self.assertNotIn('onSelect(discX', chips)
        self.assertIn('onSelect(stop)', chips)

    def test_slider_geometry_accessibility_and_direct_drag(self):
        slider = (UI / 'components/Controls.kt').read_text().split('fun IosSlider(', 1)[1].split('/**', 1)[0]
        self.assertIn('if (dragging) snap() else CameraMotion.fluid()', slider)
        self.assertIn('10.dp.toPx()', slider)
        self.assertIn('height(44.dp)', slider)
        self.assertIn('ProgressBarRangeInfo', slider)
        self.assertIn('setProgress {', slider)
        self.assertIn('position * 12f', slider)  # bounded haptic detents

    def test_capture_motion_tracks_real_busy_and_success_signals(self):
        controls = (UI / 'components/Controls.kt').read_text()
        shutter = (UI / 'components/ShutterButton.kt').read_text()
        screen = (UI / 'CameraScreen.kt').read_text()
        self.assertIn('busy = state.captureBusy', controls)
        self.assertIn('enabled = state.canUseShutter', controls)
        self.assertIn('AnimatedVisibility(visible = busy', shutter)
        self.assertIn('GalleryFlight(pulse = state.galleryPulse', screen)
        self.assertIn('thumbnail != null && from != Offset.Zero && to != Offset.Zero', screen)
        self.assertIn('Box(Modifier.fillMaxWidth().height(railHeight)', screen)
        self.assertIn('CaptureControlRail(state, actions)', screen)
        self.assertIn('text = label ?: lastLabel', screen)

    def test_browser_keeps_glass_body_without_edge_stroke(self):
        preview = (ROOT / 'design/index.html').read_text()
        self.assertNotIn('id="switch"', preview)
        self.assertNotIn('$("switch")', preview)
        self.assertIn('backdrop-filter:', preview)
        self.assertNotIn('inset 0 1px', preview)
        for control in ('portrait', 'resolution', 'shutter', 'zoomRange', 'fstop', 'ev'):
            self.assertIn(f'id="{control}"', preview)
        self.assertIn('--zoom-x', preview)
        self.assertIn('--mode-x', preview)
        self.assertIn('prefers-reduced-motion: reduce', preview)
        self.assertIn('if (reducedMotion.matches) return', preview)
        self.assertIn('Start video simulation', preview)
        self.assertIn('no photo is captured', preview)


if __name__ == '__main__':
    unittest.main()
