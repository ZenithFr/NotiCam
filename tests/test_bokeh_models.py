"""Portable contracts and fetcher tests, not an Android runtime/quality benchmark."""
import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src/com/zenith/auxcam'
module = importlib.util.spec_from_file_location('bokeh_fetcher', ROOT / 'tools/fetch_bokeh_models.py')
fetcher = importlib.util.module_from_spec(module)
module.loader.exec_module(fetcher)


class BokehModels(unittest.TestCase):
    def test_picker_and_actions_are_wired_to_real_engines(self):
        models = (SRC / 'BokehModel.kt').read_text()
        engines = (SRC / 'BokehEngines.kt').read_text()
        settings = (SRC / 'ui/components/BokehModelSettings.kt').read_text()
        for value in ('MediaPipeAuto', 'MediaPipeDual', 'MediaPipeLandscape', 'MediaPipeGeneral', 'MODNet', 'RVM'):
            self.assertIn(value, models)
            self.assertIn('BokehModel.' + value, engines)
        self.assertIn('BokehModelSettings(state, actions)', (SRC / 'ui/components/Controls.kt').read_text())
        for action in ('onBokehModelSelected', 'onRunBokehChecks(false)', 'onRunBokehChecks(true)', 'onCancelBokehChecks', 'onCopyBokehChecks'):
            self.assertIn(action, settings)
        self.assertIn('role = Role.RadioButton', settings)

    def test_selecting_model_is_persistent_and_does_not_rebuild_camera_or_reduce_still_size(self):
        controller = (SRC / 'CameraController.kt').read_text()
        select = controller.split('override fun onBokehModelSelected', 1)[1].split('override fun onRunBokehChecks', 1)[0]
        self.assertIn('putString("model", model.id).apply()', select)
        self.assertIn('++bokehModelGeneration', select)
        self.assertIn('publishedMatte.set(null)', select)
        for forbidden in ('rebuildStillSession', 'resolution =', 'stillReader', 'setZoom('):
            self.assertNotIn(forbidden, select)

    def test_checks_and_live_models_share_one_worker_and_never_hold_images(self):
        controller = (SRC / 'CameraController.kt').read_text()
        dispatch = controller.split('private fun dispatchBokeh', 1)[1].split('override fun onSettingsToggle', 1)[0]
        self.assertLess(dispatch.index('BokehInput.copy(image)'), dispatch.index('matteExecutor.execute'))
        worker = dispatch.split('matteExecutor.execute', 1)[1]
        self.assertNotIn('image.', worker)
        self.assertIn('bokehProcessor.checks', worker)
        self.assertIn('bokehProcessor.process', worker)
        self.assertIn('pendingBokehChecks.compareAndSet(request, null)', dispatch)
        self.assertIn('val checkGeneration = request?.token', dispatch)
        self.assertIn('modelGeneration == bokehModelGeneration', worker)
        self.assertIn('generation == frameGeneration', worker)
        self.assertIn('isMatteWorkerBusy.compareAndSet(false, true)', dispatch)
        self.assertIn('matteExecutor.execute { bokehProcessor.close() }', controller)
        self.assertNotIn('matteSegmenter?.close()', controller)
        self.assertNotIn('matteExecutor.awaitTermination', controller)

    def test_rvm_states_are_copied_before_result_close_and_reset(self):
        engines = (SRC / 'BokehEngines.kt').read_text()
        self.assertIn('session.run(tensors, names).use', engines)
        self.assertIn('tensor.info.shape.clone()', engines)
        self.assertIn('recurrent = emptyList()', engines)
        self.assertIn('tensors.values.forEach { it.close() }', engines)
        self.assertIn('frame.timestampNs - lastTimestamp > FrameData.MATTE_MAX_AGE_NS', (SRC / 'BokehProcessor.kt').read_text())
        self.assertIn('signed = model == BokehModel.MODNet', engines)

    def test_optional_folders_do_not_break_legacy_asset_refresh(self):
        activity = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('if (!name.endsWith(".tflite")) continue', activity)
        self.assertIn('Cannot replace model $name', activity)
        self.assertIn('packagedOnly = true', (SRC / 'BokehEngines.kt').read_text())

    def test_runtime_pass_is_not_claimed_as_quality_or_display_fps(self):
        processor = (SRC / 'BokehProcessor.kt').read_text()
        self.assertIn('repeat(5)', processor)
        self.assertIn('active.infer(frame)', processor)
        self.assertIn('PASS runtime', processor)
        self.assertIn('flat mask: inspect subject/lighting', processor)
        self.assertIn('CancellationException', processor)
        state = (SRC / 'CameraUiState.kt').read_text()
        self.assertIn('!bokehChecksRunning', state)
        gate = state.split('val canTakePhoto:', 1)[1].split('val canUseShutter:', 1)[0]
        self.assertNotIn('bokehModelReady', gate)
        self.assertNotIn('bokehModelError', gate)
        self.assertNotIn('bokehLiveSuspended', gate)

    def test_all_assets_have_licenses_and_checksums_and_are_bundled_at_build(self):
        manifest = json.loads((ROOT / 'assets/models/bokeh-models.json').read_text())
        self.assertEqual(4, len(manifest['files']))
        for spec in manifest['files']:
            self.assertEqual(64, len(spec['sha256']))
            self.assertIn(spec['license'], ('Apache-2.0', 'GPL-3.0-only'))
            path = ROOT / 'assets' / spec['asset']
            if path.exists():
                self.assertTrue(fetcher.valid(path, spec), spec['asset'])
            if spec['asset'].endswith('.onnx'):
                self.assertTrue(any('/git/blobs/' in url for url in spec['urls']))
        gradle = (ROOT / 'build.gradle.kts').read_text()
        self.assertIn('onnxruntime-android:1.20.0', gradle)
        self.assertIn('dependsOn(fetchBokehModels)', gradle)
        for name in ('MODNet-Apache-2.0.txt', 'RVM-GPL-3.0.txt', 'ONNX-Runtime-MIT.txt', 'MediaPipe-Apache-2.0.txt'):
            self.assertTrue((ROOT / 'assets/models/licenses' / name).is_file())

    def test_fetcher_rejects_corruption_and_accepts_only_verified_fallback(self):
        expected = b'verified model bytes'
        spec = dict(asset='models/optional/test.onnx', size=len(expected), sha256=hashlib.sha256(expected).hexdigest(),
                    urls=['https://github.com/example/test/releases/model', 'https://api.github.com/repos/example/test/git/blobs/pin'])
        blob = json.dumps({'encoding': 'base64', 'content': base64.b64encode(expected).decode()}).encode()
        with patch.object(fetcher.urllib.request, 'urlopen', side_effect=[io.BytesIO(b'wrong'), io.BytesIO(blob)]) as network:
            self.assertEqual(expected, fetcher.fetch(spec))
            self.assertEqual(2, network.call_count)
        with patch.object(fetcher.urllib.request, 'urlopen', side_effect=[io.BytesIO(b'wrong'), io.BytesIO(blob.replace(b'base64', b'wrong!'))]):
            self.assertRaises(RuntimeError, fetcher.fetch, spec)

    def test_valid_cache_checks_hash_not_just_file_length(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'model'
            path.write_bytes(b'abc')
            spec = dict(size=3, sha256=hashlib.sha256(b'abc').hexdigest())
            self.assertTrue(fetcher.valid(path, spec))
            path.write_bytes(b'def')
            self.assertFalse(fetcher.valid(path, spec))


if __name__ == '__main__':
    unittest.main()
