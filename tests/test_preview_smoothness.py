"""Preview/still isolation and handoff contracts. Not device FPS or Android runtime tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'


class PreviewSmoothness(unittest.TestCase):
    def test_rate_and_duration_are_both_checked_without_high_speed_session(self):
        caps = (SRC / 'CameraCapabilities.java').read_text()
        preview = caps.split('static android.util.Range<Integer> previewRange', 1)[1].split('static Size videoSize', 1)[0]
        self.assertIn('CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES', preview)
        self.assertIn('getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)', preview)
        self.assertIn('upper > 30 && duration <= 0', preview)
        self.assertIn('size.getWidth() > 1440 || size.getHeight() > 1080', preview)
        self.assertIn('4.0 / 3.0', preview)
        service = (SRC / 'CameraUserService.java').read_text()
        self.assertNotIn('SESSION_HIGH_SPEED', service)
        self.assertNotIn('createConstrainedHighSpeedCaptureSession', service)
        self.assertIn('mPreviewMaxFps = previewMaxFps >= 60 && recordingSurface == null ? 60 : 30', service)
        self.assertIn('mPreviewFpsRange != null', service)
        self.assertIn('float requestedZoom, int previewMaxFps', (SRC / 'IAuxCamService.aidl').read_text())
        self.assertIn('videoMode, requestedZoom, previewMaxFps));', service)
        self.assertIn('.version(9)', (SRC / 'CameraController.kt').read_text())

    def test_new_preview_profile_never_sizes_a_still_reader(self):
        controller = (SRC / 'CameraController.kt').read_text()
        config = controller.split('private fun configurePreviewReader', 1)[1].split('private fun startStereoSession', 1)[0]
        self.assertIn('ImageReader.newInstance(profile[0], profile[1]', config)
        self.assertNotIn('stillReader =', config)
        still = controller.split('private fun createStillReader()', 1)[1].split('private fun rebuildStillSession', 1)[0]
        self.assertIn('getStandardJpegSize(currentLens)', still)
        self.assertIn('state.highResolutionWidth', still)
        self.assertNotIn('previewWidth', still)
        self.assertNotIn('previewHeight', still)
        self.assertIn('output.write(jpeg)', controller)
        service = (SRC / 'CameraUserService.java').read_text()
        capture = service.split('private void captureStillOnCameraThread', 1)[1].split('void onCaptureResult', 1)[0]
        self.assertIn('JPEG_QUALITY, (byte) 100', capture)
        self.assertIn('mZoomRatio = mRequestedZoom', capture)
        self.assertNotIn('CONTROL_AE_TARGET_FPS_RANGE', capture)

    def test_lens_bridge_is_bounded_and_revealed_only_by_fresh_successful_frames(self):
        controller = (SRC / 'CameraController.kt').read_text()
        self.assertIn('PixelCopy.request(holder.surface, bitmap', controller)
        self.assertIn('main.postDelayed(timeout, 200)', controller)
        self.assertIn('AtomicBoolean(false)', controller)
        self.assertIn('token != zoomHandoffToken', controller)
        self.assertIn('if (rendered && reader === colorReader && renderToken == sessionToken)', controller)
        self.assertIn('if (count == 4)', controller)
        self.assertIn('zoomBlendReady = true', controller)
        self.assertIn('cancelZoomHandoff()', controller.split('fun pause()', 1)[1].split('fun dispose()', 1)[0])
        self.assertIn('queuedZoom = zoom', controller)
        self.assertIn('if (pendingRebuildToken != null) return', controller)
        self.assertIn('pendingRebuildToken = generation', controller)
        self.assertIn('if (pendingRebuildToken == generation) pendingRebuildToken = null', controller)
        blend = (SRC / 'ui/components/LensBlend.kt').read_text()
        self.assertIn('repeat(2) { withFrameNanos', blend)
        self.assertIn('progress.animateTo(1f, tween(360', blend)
        self.assertIn('actions.onZoomBlendFinished(state.zoomBlendToken)', blend)
        self.assertIn('LensBlend(state, actions', (SRC / 'ui/CameraScreen.kt').read_text())

    def test_zoom_out_moves_both_layers_on_the_same_clock(self):
        blend = (SRC / 'ui/components/LensBlend.kt').read_text()
        self.assertIn('CameraZoom.incomingPreviewScale(ratio, progress.value, snapshotStartScale)', blend)
        self.assertIn('CameraZoom.outgoingSnapshotScale(ratio, progress.value, snapshotStartScale)', blend)
        self.assertIn('alpha = 1f - progress.value', blend)
        self.assertNotIn('delay(80)', blend)
        # Live content is unconditional: snapshot arrival/removal must not replace
        # the AndroidView / destroy its SurfaceHolder in the middle of a handoff.
        self.assertLess(blend.index('liveContent()'), blend.index('if (image != null) {\n            Image'))
        screen = (SRC / 'ui/CameraScreen.kt').read_text()
        self.assertIn('LensBlend(state, actions, Modifier.fillMaxSize()) {', screen)
        self.assertIn('!state.previewTransitioning && !state.captureBusy', screen)

    def test_pinch_stays_active_and_accumulates_during_handoff(self):
        view = (SRC / 'ui/components/Viewfinder.kt').read_text()
        self.assertIn('.pointerInput(zoomEnabled)', view)
        screen = (SRC / 'ui/CameraScreen.kt').read_text()
        self.assertIn('(state.sessionReady || state.previewTransitioning)', screen)
        controller = (SRC / 'CameraController.kt').read_text()
        self.assertIn('applyZoom((queuedZoom ?: state.zoom) * scaleFactor', controller)
        self.assertIn('applyZoom((queuedZoom ?: state.zoom) * exp(deltaPx / 180f)', controller)
        self.assertIn('val target = queuedZoom ?: toZoom', controller)
        self.assertIn('switch(target)', controller)
        ready = controller.split('if (count == 4) post {', 1)[1].split('if (renderedCounterToken', 1)[0]
        self.assertLess(ready.index('selectLens(CameraZoom.lensForZoom(destination))'),
                        ready.index('zoomBlendReady = true'))

    def test_unavailable_60_retries_only_preview_policy_and_retains_jpeg(self):
        controller = (SRC / 'CameraController.kt').read_text()
        error = controller.split('private fun onSessionError', 1)[1].split('if (state.videoStarting', 1)[0]
        self.assertIn('preview30Fallbacks.add(previewKey())', error)
        self.assertIn('same still quality', error)
        self.assertNotRegex(error, r'\bresolution\s*=(?!=)')
        self.assertIn('No fresh viewfinder frames', controller)
        self.assertIn('previewMeasuredFps = fps', controller)
        self.assertIn('RENDERED · %.0f FPS · SENSOR', controller)

    def test_sharp_gpu_shortcut_precedes_upload_and_keeps_still_path_separate(self):
        native = (ROOT / 'jni/src/VulkanPipeline.cpp').read_text()
        entry = native.split('bool VulkanPipeline::renderFrame(', 1)[1].split('bool VulkanPipeline::presentSharpPreview', 1)[0]
        self.assertLess(entry.index('return presentSharpPreview('), entry.index('vkMapMemory(mDevice, mUploadStagingMem'))
        sharp = native.split('bool VulkanPipeline::presentSharpPreview(', 1)[1].split('uint32_t VulkanPipeline::findMemoryType', 1)[0]
        for forbidden in ('mPipeGather', 'mPipeAlpha', 'mPipeCoc', 'mUploadStagingBuffer', 'vkCmdCopyImageToBuffer'):
            self.assertNotIn(forbidden, sharp)
        self.assertIn('mPipeComp', sharp)
        self.assertIn('kPreviewRotate90 | 2', sharp)
        self.assertLess(sharp.index('vkWaitForFences'), sharp.index('releaseImportedImage'))
        self.assertIn('vkQueuePresentKHR', sharp)
        self.assertIn('captureDesc.width != mFullW', native)

    def test_zoom_easing_is_in_lens_and_final_still_uses_exact_target(self):
        zoom = (SRC / 'CameraZoom.java').read_text()
        self.assertIn('if (zoom < 1f) return "2"', zoom)
        smooth = zoom.split('public static float smoothPreviewZoom', 1)[1].split('public static float digitalRatio', 1)[0]
        self.assertIn('Math.log((double) target / current)', smooth)
        self.assertNotIn('lensForZoom', smooth)
        service = (SRC / 'CameraUserService.java').read_text()
        self.assertIn('CameraZoom.smoothPreviewZoom', service)
        self.assertIn('mCameraHandler.postDelayed(this, 16)', service)
        self.assertIn('mCameraHandler.removeCallbacks(mZoomStep)', service)
        close = service.split('private void closeSessionOnCameraThread()', 1)[1].split('private void notifyError', 1)[0]
        self.assertIn('removeCallbacks(mZoomStep)', close)

    def test_fixed_1080_buffer_display_hint_and_simulation_label(self):
        view = (SRC / 'ui/components/Viewfinder.kt').read_text()
        self.assertIn('holder.setFixedSize(DeviceProfile.COLOR_HEIGHT, DeviceProfile.COLOR_WIDTH)', view)
        self.assertIn('Build.VERSION.SDK_INT >= 30', view)
        self.assertIn('setFrameRate(60f', view)
        state = (SRC / 'CameraUiState.kt').read_text()
        self.assertIn('!previewTransitioning', state)
        preview = (ROOT / 'design/index.html').read_text()
        self.assertIn('animation.finished.then(clean, clean)', preview)
        self.assertIn('no camera FPS is measured here', preview)
        self.assertIn('reducedMotion.matches || lensForZoom(from)', preview)


if __name__ == '__main__':
    unittest.main()
