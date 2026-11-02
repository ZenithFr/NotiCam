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
    def test_only_requested_model_is_exposed_and_unavailable_is_explicit(self):
        models = (SRC / 'BokehModel.kt').read_text()
        self.assertIn('MobileNetV4Int8(', models)
        self.assertNotIn('MediaPipeAuto(', models)
        self.assertNotIn('MODNet(', models)
        engines = (SRC / 'BokehEngines.kt').read_text()
        self.assertIn('throw PortraitUnavailableException', engines)
        for forbidden in ('Interpreter(', 'NnApiDelegate(', 'OrtEnvironment', 'CPU/XNNPACK', 'OnnxBokehEngine'):
            self.assertNotIn(forbidden, engines)
        settings = (SRC / 'ui/components/BokehModelSettings.kt').read_text()
        self.assertIn('PortraitRuntime.blockedReason', settings)
        self.assertIn('INT8', settings)

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

    def test_legacy_models_and_fallback_implementation_removed(self):
        engines = (SRC / 'BokehEngines.kt').read_text()
        for retired in ('MediaPipeDual', 'MediaPipeLandscape', 'MediaPipeGeneral', 'BokehModel.RVM', 'DualMatteSegmenter'):
            self.assertNotIn(retired, engines)
        self.assertFalse((SRC / 'InferenceBackend.kt').exists())
        self.assertFalse((SRC / 'DualMatteSegmenter.kt').exists())
        self.assertEqual([], list((ROOT / 'assets/models').glob('*.tflite')))

    def test_optional_folders_do_not_break_legacy_asset_refresh(self):
        activity = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('if (!name.endsWith(".tflite")) continue', activity)
        self.assertIn('Cannot replace model $name', activity)
        self.assertIn('PortraitRuntime.blockedReason', (SRC / 'BokehEngines.kt').read_text())

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

    def test_no_untrained_or_unlicensed_substitute_is_bundled(self):
        manifest = json.loads((ROOT / 'assets/models/bokeh-models.json').read_text())
        self.assertEqual([], manifest['files'])
        gradle = (ROOT / 'build.gradle.kts').read_text()
        self.assertNotIn('onnxruntime-android:', gradle)
        self.assertIn('dependsOn(fetchBokehModels)', gradle)
        self.assertIn('"models/optional/modnet.onnx"', gradle)

    def test_retired_generated_weight_is_pruned_without_touching_other_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            optional = root / 'assets/models/optional'
            optional.mkdir(parents=True)
            retired = optional / 'rvm_mobilenetv3_fp32.onnx'
            current = optional / 'modnet.onnx'
            custom = optional / 'my_audit_model.onnx'
            for path in (retired, current, custom):
                path.write_bytes(b'fixture')
            fetcher.prune_retired_assets(root)
            self.assertFalse(retired.exists())
            self.assertFalse(current.exists())
            self.assertTrue(custom.exists())
            fetcher.prune_retired_assets(root)  # Idempotent.
        self.assertIn('assets.exclude("models/optional/rvm_mobilenetv3_fp32.onnx",', (ROOT / 'build.gradle.kts').read_text())

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
