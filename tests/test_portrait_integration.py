"""Integration source contracts + executable host test, not device/Vulkan proof."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'


class PortraitIntegration(unittest.TestCase):
    def test_fence_is_not_reset_before_acquire_and_both_paths_check_submission(self):
        native = (ROOT / 'jni/src/VulkanPipeline.cpp').read_text()
        render = native.split('bool VulkanPipeline::renderFrame(', 1)[1].split('bool VulkanPipeline::presentSharpPreview(', 1)[0]
        self.assertNotIn('vkResetFences', render.split('vkAcquireNextImageKHR', 1)[0])
        self.assertIn('res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR', render)
        self.assertEqual(2, native.count('completePreviewSubmission(VK_SUCCESS,'))
        failure = render.split('if (submissionResult != VK_SUCCESS)', 1)[1].split('auto tSub1', 1)[0]
        self.assertIn('recreateSwapchain()', failure)
        self.assertNotIn('vkWaitForFences', failure)
        self.assertIn('pcAlpha.gateBypass = hasDepthSignal ? curGateBypass : 1u', render)

    @unittest.skipUnless(shutil.which('g++') or shutil.which('clang++'), 'Host C++ compiler not installed')
    def test_native_submission_success_and_three_failure_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / 'preview-submission-test'
            subprocess.run([shutil.which('g++') or shutil.which('clang++'), '-std=c++17', '-Wall', '-Wextra',
                            '-Werror', '-pedantic', '-I', str(ROOT / 'jni/src'),
                            str(ROOT / 'tests/native/PreviewSubmissionTest.cpp'), '-o', str(binary)], check=True)
            subprocess.run([str(binary)], check=True, timeout=5)

    def test_diagnostics_are_not_automatic_preview_work(self):
        native = (ROOT / 'jni/src/VulkanPipeline.cpp').read_text()
        self.assertIn('sBisectConfig = BisectConfig::DONE;', native)
        self.assertIn('sEdgeState = EdgeRefineState::HOLD_3;', native)
        self.assertIn('if ((doTriggerAb || sEdgeRefineOverride >= 0)', native)
        bridge = (ROOT / 'jni/src/NativeBokehBridge.cpp').read_text()
        notify = bridge.split('Java_com_zenith_auxcam_MainActivity_nativeNotifyMatteDrop', 1)[1].split('\n}', 1)[0]
        self.assertIn('std::try_to_lock', notify)

    def test_still_uses_its_own_copied_input_frozen_model_and_noninterrupting_timeout(self):
        controller = (SRC / 'CameraController.kt').read_text()
        still = controller.split('private fun portraitMatteForStill(', 1)[1].split('private fun onStillImage(', 1)[0]
        self.assertLess(still.index('BokehInput.copy(image)'), still.index('matteExecutor.submit'))
        worker = still.split('matteExecutor.submit', 1)[1].split('pending.get', 1)[0]
        self.assertNotIn('image.', worker)
        self.assertIn('frozen.bokehModel', worker)
        self.assertIn('pending.get(3000, java.util.concurrent.TimeUnit.MILLISECONDS)', still)
        self.assertIn('pending.cancel(false)', still)
        capture = controller.split('private fun onStillImage(', 1)[1].split('private fun receiveCameraJpeg(', 1)[0]
        self.assertIn('portraitMatteForStill(image, frozen)', capture)
        self.assertNotIn('matteFor(image.timestamp)', capture)
        self.assertIn('frozen.bokehActive && portraitApplied', capture)
        self.assertIn('Saved sharp photo', capture)
        self.assertIn('!state.bokehActive && state.resolution == Resolution.Standard', controller)

    def test_request_failure_is_not_success_and_does_not_disable_healthy_session(self):
        service = (SRC / 'CameraUserService.java').read_text().split('static class StillCaptureCallback', 1)[1]
        failed = service.split('public void onCaptureFailed', 1)[1]
        self.assertNotIn('onStillCompleted()', failed)
        self.assertIn('notifyError("Still capture failed:', failed)
        self.assertIn('onCaptureSequenceAborted', failed)
        self.assertIn('mGeneration != mService.mSessionGeneration', failed)
        controller = (SRC / 'CameraController.kt').read_text()
        recover = controller.split('private fun onSessionError', 1)[1].split('main.removeCallbacks(previewFrameTimeout)', 1)[0]
        self.assertIn('startsWith("Still capture failed:")', recover)
        self.assertIn('finishCapture(', recover)
        self.assertNotIn('sessionReady = false', recover)
        self.assertNotIn('lastShutterTriggerNs', controller)
        self.assertIn('stillCaptureInProgress.compareAndSet(false, true)', controller)

    def test_live_work_yields_during_capture_and_snapshot_is_read_once_for_diagnostics(self):
        controller = (SRC / 'CameraController.kt').read_text()
        dispatch = controller.split('private fun dispatchBokeh', 1)[1].split('override fun onSettingsToggle', 1)[0]
        self.assertIn('!current.captureBusy', dispatch)
        self.assertIn('THREAD_PRIORITY_BACKGROUND', controller)
        diagnostic = controller.split('val diagnosticMatte = publishedMatte.get()?.bytes', 1)[1].split('alphaBmp.setPixels', 1)[0]
        self.assertNotIn('publishedMatte.get()', diagnostic)
