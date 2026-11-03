"""Synthetic fixtures test the audit gate, not Android execution or APK validity."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('android_output_audit', ROOT / 'tools/audit_android_outputs.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class AndroidOutputAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write('build/test-results/testDebugUnitTest/TEST-policy.xml', '<testsuite tests="129" failures="0" errors="0" skipped="0"/>')
        for variant in ('debug', 'release'):
            self.write(f'build/reports/lint-results-{variant}.xml', '<issues/>')
            self.apk(variant)
        self.write('jni/shaders/preview.comp', '// Fixture shader')

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        return target

    @staticmethod
    def elf(alignment=16384):
        data = bytearray(120)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', data, 18, 183)
        struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, 1)
        struct.pack_into('<I', data, 64, 1)
        struct.pack_into('<Q', data, 112, alignment)
        return data

    def apk(self, variant, omit=None, shader=b'\x03\x02\x23\x07', alignment=16384):
        target = self.root / f'build/outputs/apk/{variant}/fixture-{variant}.apk'
        target.parent.mkdir(parents=True, exist_ok=True)
        files = {'classes.dex': b'fixture', 'AndroidManifest.xml': b'fixture',
                 'assets/shaders/preview.spv': shader}
        for name in ('auxcam_engine', 'c++_shared', 'tensorflowlite_jni'):
            files[f'lib/arm64-v8a/lib{name}.so'] = self.elf(alignment)
        with zipfile.ZipFile(target, 'w') as archive:
            for name, content in files.items():
                if name != omit:
                    archive.writestr(name, content)
        return target

    def run_audit(self):
        with patch.object(audit, 'ROOT', self.root), patch.object(audit, 'emit') as output:
            audit.main()
            return output.call_args_list

    def test_complete_fixtures_pass(self):
        output = self.run_audit()
        self.assertEqual(2, sum(call.args[1] == 'APK contents verified' for call in output))

    def test_no_source_is_not_success(self):
        (self.root / 'build/test-results/testDebugUnitTest/TEST-policy.xml').unlink()
        with self.assertRaisesRegex(ValueError, 'No real Gradle JUnit'):
            self.run_audit()

    def test_failed_tests_fail(self):
        self.write('build/test-results/testDebugUnitTest/TEST-policy.xml', '<testsuite tests="129" failures="1"/>')
        with self.assertRaisesRegex(ValueError, 'Incomplete/failing'):
            self.run_audit()

    def test_skipped_tests_do_not_satisfy_execution_floor(self):
        self.write('build/test-results/testDebugUnitTest/TEST-policy.xml', '<testsuite tests="129" skipped="1"/>')
        with self.assertRaisesRegex(ValueError, 'Incomplete/failing'):
            self.run_audit()

    def test_missing_lint_report_fails(self):
        (self.root / 'build/reports/lint-results-release.xml').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing release lint report'):
            self.run_audit()

    def test_lint_error_fails(self):
        self.write('build/reports/lint-results-debug.xml', '<issues><issue severity="Error" id="NewApi" message="Fixture"><location file="fixture.kt" line="12"/></issue></issues>')
        with self.assertRaisesRegex(ValueError, 'lint contains 1 errors'):
            self.run_audit()

    def test_missing_release_apk_fails(self):
        (self.root / 'build/outputs/apk/release/fixture-release.apk').unlink()
        with self.assertRaisesRegex(ValueError, 'No release APK'):
            self.run_audit()

    def test_missing_shader_fails(self):
        self.apk('debug', omit='assets/shaders/preview.spv')
        with self.assertRaisesRegex(ValueError, 'missing .*preview.spv'):
            self.run_audit()

    def test_invalid_shader_fails(self):
        self.apk('debug', shader=b'not spir-v')
        with self.assertRaisesRegex(ValueError, 'invalid SPIR-V'):
            self.run_audit()

    def test_missing_library_fails(self):
        self.apk('debug', omit='lib/arm64-v8a/libauxcam_engine.so')
        with self.assertRaisesRegex(ValueError, 'missing .*libauxcam_engine'):
            self.run_audit()

    def test_wrong_architecture_fails(self):
        data = self.elf()
        struct.pack_into('<H', data, 18, 62)
        with self.assertRaisesRegex(ValueError, 'Expected AArch64'):
            audit.load_alignments(data)

    def test_4k_library_is_reported_as_conditional_risk(self):
        self.apk('debug', alignment=4096)
        output = self.run_audit()
        self.assertEqual(3, sum(call.args[0] == 'warning' for call in output))
