"""Read-only metadata/reporting contracts; not camera session or throughput tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/com/zenith/auxcam/CameraCapabilities.java'


class CameraDiagnostics(unittest.TestCase):
    def setUp(self):
        self.source = SOURCE.read_text()

    def test_report_distinguishes_hal_blob_private_yuv_and_java_jpeg(self):
        for text in ('RAW_SENSOR/RAW16=32 (0x20)', 'BLOB=33 (0x21; commonly JPEG)',
                     'PRIVATE=34 (0x22)', 'YUV_420_888=35 (0x23)', 'Java JPEG=256 (0x100)',
                     '33/35 entries do not prove RAW', 'final 0/1 field has no assumed meaning'):
            self.assertIn(text, self.source)
        raw = self.source.split('private static void appendRawMap', 1)[1].split('private static void appendSensorMetadata', 1)[0]
        self.assertIn('ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12', raw)
        self.assertNotIn('nothing.scaler', raw)
        self.assertNotIn('format == 33', raw)

    def test_default_slow_and_maximum_resolution_raw_maps_are_distinct(self):
        self.assertIn('appendRawMap(out, "DEFAULT pixel mode", map)', self.source)
        footage = self.source.split('private static void appendFootageCapabilities', 1)[1].split('private static void appendEncoderCapabilities', 1)[0]
        self.assertIn('if (Build.VERSION.SDK_INT >= 31)', footage)
        self.assertIn('"MAXIMUM_RESOLUTION pixel mode"', footage)
        self.assertIn('SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION', footage)
        self.assertIn('SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION', footage)
        self.assertIn('getHighResolutionOutputSizes(format)', self.source)
        self.assertIn('map not exposed', self.source)

    def test_timing_failures_are_independent_and_zero_does_not_invent_fps(self):
        sizes = self.source.split('private static void appendRawSizes', 1)[1].split('private static void appendRawMap', 1)[0]
        self.assertIn('getOutputMinFrameDuration(format, size)', sizes)
        self.assertIn('getOutputStallDuration(format, size)', sizes)
        self.assertIn('if (duration > 0)', sizes)
        self.assertIn('theoreticalMaxFps=unknown', sizes)
        self.assertGreaterEqual(sizes.count('catch (Exception e)'), 2)
        self.assertIn('if (format == ImageFormat.RAW_SENSOR)', sizes)
        self.assertIn('excludingRowPadding', sizes)
        self.assertIn('signal level, not storage bit depth', self.source)

    def test_calibration_manual_and_stabilization_metadata_are_reported(self):
        sensor = self.source.split('private static void appendSensorMetadata', 1)[1].split('private static void appendFootageCapabilities', 1)[0]
        for key in ('SENSOR_INFO_SENSITIVITY_RANGE', 'SENSOR_MAX_ANALOG_SENSITIVITY',
                    'SENSOR_INFO_EXPOSURE_TIME_RANGE', 'SENSOR_INFO_MAX_FRAME_DURATION',
                    'SENSOR_INFO_COLOR_FILTER_ARRANGEMENT', 'SENSOR_BLACK_LEVEL_PATTERN', 'SENSOR_INFO_WHITE_LEVEL',
                    'SENSOR_COLOR_TRANSFORM1', 'SENSOR_COLOR_TRANSFORM2', 'SENSOR_CALIBRATION_TRANSFORM1',
                    'SENSOR_FORWARD_MATRIX1', 'SENSOR_REFERENCE_ILLUMINANT1',
                    'REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR', 'CONTROL_AE_AVAILABLE_MODES',
                    'CONTROL_AWB_AVAILABLE_MODES', 'CONTROL_AF_AVAILABLE_MODES',
                    'LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION', 'CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES'):
            self.assertIn(key, sensor)

    def test_high_speed_heic_and_encoder_inputs_are_not_claimed_as_working_modes(self):
        for text in ('getHighSpeedVideoSizes()', 'getHighSpeedVideoFpsRangesFor(size)',
                     'not a regular/full-field/RAW recording guarantee', 'ImageFormat.HEIC',
                     'not a verified HEIC encoder/session', 'caps.colorFormats', 'surfaceInput=',
                     'getWidthAlignment()', 'getHeightAlignment()', 'getSupportedFrameRates()',
                     'not proof of RAW video throughput'):
            self.assertIn(text, self.source)

    def test_optional_query_failure_does_not_suppress_remaining_metadata(self):
        metadata = self.source.split('private static void appendMetadata', 1)[1].split('private static boolean appendCapability', 1)[0]
        self.assertIn('c.get(key)', metadata)
        self.assertIn('catch (Exception e)', metadata)
        self.assertIn('not exposed', metadata)
        self.assertIn('value instanceof float[]', metadata)
        for text in ('Maximum-resolution RAW map unavailable:', 'P010 sizes unavailable:',
                     'High-speed FPS query unavailable:', '50 MP discovery unavailable:',
                     'Recorder candidate unavailable:', 'Preview profile unavailable:',
                     'Default stream map unavailable:', 'Maximum JPEG metadata unavailable:'):
            self.assertIn(text, self.source)

    def test_diagnostic_helpers_do_not_open_capture_record_or_change_settings(self):
        diagnostic = self.source.split('private static void appendRawSizes', 1)[1]
        for forbidden in ('openCamera(', 'createCaptureSession(', 'ImageReader.newInstance(',
                          'createEncoderByType(', 'new CaptureRequest.Key', 'Settings.Global.put',
                          'SystemProperties.set', 'setRepeatingRequest('):
            self.assertNotIn(forbidden, diagnostic)
        self.assertIn('Read-only report: no streams opened', diagnostic)
        self.assertIn('not measured delivery', diagnostic)


if __name__ == '__main__':
    unittest.main()
