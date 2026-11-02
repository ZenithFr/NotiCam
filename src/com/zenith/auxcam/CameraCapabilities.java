package com.zenith.auxcam;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.graphics.Rect;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.os.Build;
import android.util.Size;
import android.util.SizeF;
import java.util.Arrays;

/** Read-only discovery. Never invent sensor dimensions or write guessed vendor controls. */
final class CameraCapabilities {
    static final class Target {
        final String cameraId, source;
        final Size size;
        final int pixelMode;
        Target(String id, Size size, int mode, String source) {
            this.cameraId = id; this.size = size; this.pixelMode = mode; this.source = source;
        }
    }
    static boolean is50Mp(Size s) {
        long pixels = (long) s.getWidth() * s.getHeight();
        double aspect = (double) Math.max(s.getWidth(), s.getHeight()) / Math.min(s.getWidth(), s.getHeight());
        return pixels >= 45_000_000L && pixels <= 55_000_000L && Math.abs(aspect - 4.0 / 3.0) < .02;
    }
    private static Size best(Size[] sizes, boolean high) {
        Size result = null;
        if (sizes != null) for (Size s : sizes) {
            long pixels = (long) s.getWidth() * s.getHeight();
            double aspect = (double) s.getWidth() / s.getHeight();
            if ((high ? is50Mp(s) : pixels <= 20_000_000L && Math.abs(aspect - 4.0 / 3.0) < .02) &&
                    (result == null || pixels > (long) result.getWidth() * result.getHeight())) result = s;
        }
        return result;
    }
    static Size jpegSize(StreamConfigurationMap map, boolean high) {
        if (map == null) return null;
        Size ordinary = best(map.getOutputSizes(ImageFormat.JPEG), high);
        Size slow = best(map.getHighResolutionOutputSizes(ImageFormat.JPEG), high);
        if (ordinary == null) return slow;
        if (slow == null) return ordinary;
        return (long) ordinary.getWidth() * ordinary.getHeight() >= (long) slow.getWidth() * slow.getHeight() ? ordinary : slow;
    }
    private static double fieldOfViewRatio(CameraCharacteristics c) {
        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        return focal != null && focal.length > 0 && sensor != null && sensor.getWidth() > 0 ? focal[0] / sensor.getWidth() : 0;
    }
    /** Enumerated IDs plus the device IDs this app already uses. No blind numeric probing.
     * Queryable characteristics do NOT guarantee a successful camera/session open.
     * Physical-only IDs remain diagnostic; they are never added as standalone targets.
     */
    private static java.util.LinkedHashSet<String> candidateIds(CameraManager cm) throws Exception {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        ids.add("0");
        ids.addAll(Arrays.asList(cm.getCameraIdList()));
        ids.addAll(Arrays.asList("2", "3", "5")); // Existing ultrawide, tele and stereo routes.
        return ids;
    }
    static Target discover(CameraManager cm) throws Exception {
        double mainFov = fieldOfViewRatio(cm.getCameraCharacteristics("0"));
        // Prefer main, then other queryable rear main-FOV IDs. A telephoto
        // 50 MP output must not masquerade as a 1x main-camera image.
        java.util.LinkedHashSet<String> ids = candidateIds(cm);
        for (String id : ids) try {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            double fov = fieldOfViewRatio(c);
            if (!"0".equals(id) && (mainFov == 0 || fov == 0 || Math.abs(fov / mainFov - 1) > .15)) continue;
            Size s = jpegSize(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), true);
            if (s != null) return new Target(id, s, CaptureRequest.SENSOR_PIXEL_MODE_DEFAULT, "Camera2 default/high-resolution map");
            if (Build.VERSION.SDK_INT >= 31 && c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION) != null) {
                s = jpegSize(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION), true);
                if (s != null) return new Target(id, s, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, "Camera2 maximum-resolution map");
            }
            try {
                CameraCharacteristics.Key<int[]> ntKey = new CameraCharacteristics.Key<>("nothing.scaler.availableStreamConfigurations", int[].class);
                int[] ntSizes = c.get(ntKey);
                if (ntSizes != null) {
                    for (int i = 0; i + 3 < ntSizes.length; i += 4) {
                        int fmt = ntSizes[i];
                        int w = ntSizes[i + 1];
                        int h = ntSizes[i + 2];
                        int io = ntSizes[i + 3];
                        if ((fmt == 33 || fmt == ImageFormat.JPEG) && io == 0 && is50Mp(new Size(w, h))) {
                            return new Target(id, new Size(w, h), CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, "Nothing camera 50 MP stream map");
                        }
                    }
                }
            } catch (Throwable ignored) {}
            // Vendor output tables and extension sizes are diagnostic only.
            // Without documented remosaic/session controls, requesting a larger
            // JPEG could merely upscale a binned sensor readout.
        } catch (Exception ignored) { /* Another queryable ID may still work. */ }
        return null;
    }

    static Target discover(CameraManager cm, String preferredLensId) throws Exception {
        if (preferredLensId == null || preferredLensId.isEmpty() || "0".equals(preferredLensId)) {
            return discover(cm);
        }
        try {
            CameraCharacteristics c = cm.getCameraCharacteristics(preferredLensId);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                Size s = jpegSize(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), true);
                if (s != null) return new Target(preferredLensId, s, CaptureRequest.SENSOR_PIXEL_MODE_DEFAULT, "Camera2 default/high-resolution map");
                if (Build.VERSION.SDK_INT >= 31 && c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION) != null) {
                    s = jpegSize(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION), true);
                    if (s != null) return new Target(preferredLensId, s, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, "Camera2 maximum-resolution map");
                }
                try {
                    CameraCharacteristics.Key<int[]> ntKey = new CameraCharacteristics.Key<>("nothing.scaler.availableStreamConfigurations", int[].class);
                    int[] ntSizes = c.get(ntKey);
                    if (ntSizes != null) {
                        for (int i = 0; i + 3 < ntSizes.length; i += 4) {
                            int fmt = ntSizes[i];
                            int w = ntSizes[i + 1];
                            int h = ntSizes[i + 2];
                            int io = ntSizes[i + 3];
                            if ((fmt == 33 || fmt == ImageFormat.JPEG) && io == 0 && is50Mp(new Size(w, h))) {
                                return new Target(preferredLensId, new Size(w, h), CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, "Nothing camera 50 MP stream map");
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Exception ignored) { /* Not supported on this lens */ }
        return null;
    }

    /** Default-pixel-mode RAW only. Maximum-resolution RAW needs a distinct session path. */
    static Size rawSize(CameraManager cm, String lensId) {
        Size high = rawSize(cm, lensId, true);
        return high != null ? high : rawSize(cm, lensId, false);
    }

    static Size rawSize(CameraManager cm, String lensId, boolean high) {
        try {
            String id = "2".equals(lensId) || "3".equals(lensId) ? lensId : "0";
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean raw = false;
            if (capabilities != null) for (int capability : capabilities)
                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) raw = true;
            if (!raw) return null;
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            if (map != null) for (Size[] sizes : new Size[][]{map.getOutputSizes(ImageFormat.RAW_SENSOR), map.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)}) {
                if (sizes != null) for (Size size : sizes) {
                    long pixels = (long) size.getWidth() * size.getHeight();
                    if (high != (pixels >= 45_000_000L)) continue;
                    // 12 MP means a native binned class, never a 50 MP file with a 12 MP label.
                    if (!high && (pixels < 10_000_000L || pixels > 16_000_000L)) continue;
                    if (RawFrameMatcher.maxFramesForSize(size.getWidth(), size.getHeight()) == 0) continue;
                    if (best == null || pixels > (long) best.getWidth() * best.getHeight()) best = size;
                }
            }
            return best;
        } catch (Exception ignored) { return null; }
    }
    /** Normal Camera2 session, never constrained high-speed (which would drop full-quality stills).
     * Keep the sensor's full 4:3 field, capped at 1440x1080 / 1080 sensor lines.
     * FPS ranges and YUV frame duration must both permit the selected rate.
     */
    static android.util.Range<Integer> previewRange(CameraManager cm, String id, Size size, int ceiling) throws Exception {
        CameraCharacteristics c = cm.getCameraCharacteristics(id);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        boolean advertised = false;
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes != null) for (Size candidate : sizes) if (candidate.equals(size)) advertised = true;
        if (!advertised) return null;
        long duration = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size);
        android.util.Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        android.util.Range<Integer> best = null;
        if (ranges != null) for (android.util.Range<Integer> range : ranges) {
            int upper = range.getUpper();
            if (upper > ceiling || upper <= 0 || (upper != 60 && upper > 30)) continue;
            // Unknown duration does not establish 60 fps support. It may still be tried at <=30.
            if (upper > 30 && duration <= 0) continue;
            if (duration > 0 && duration > 1_000_000_000L / upper + 1) continue;
            if (best == null || upper > best.getUpper() ||
                    (upper == best.getUpper() && range.getLower() > best.getLower())) best = range;
        }
        return best;
    }
    static int[] previewConfig(CameraManager cm, String id, boolean prefer60) throws Exception {
        StreamConfigurationMap map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new int[0];
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        Size best = null;
        android.util.Range<Integer> rate = null;
        if (sizes != null) for (Size size : sizes) {
            if (size.getWidth() > 1440 || size.getHeight() > 1080 ||
                    Math.abs((double) size.getWidth() / size.getHeight() - 4.0 / 3.0) > .02) continue;
            android.util.Range<Integer> candidate = previewRange(cm, id, size, prefer60 ? 60 : 30);
            if (candidate == null) continue;
            // Preserve 1080-line detail across lenses rather than silently selecting
            // a tiny 60 fps stream. Prefer 60 within the largest eligible preview size.
            long pixels = (long) size.getWidth() * size.getHeight();
            long previous = best == null ? 0 : (long) best.getWidth() * best.getHeight();
            if (best == null || pixels > previous || (pixels == previous && candidate.getUpper() > rate.getUpper())) {
                best = size; rate = candidate;
            }
        }
        return best == null ? new int[0] : new int[]{best.getWidth(), best.getHeight(), rate.getLower(), rate.getUpper()};
    }
    /** A declared constant capture rate, never an invented [30,60] range. */
    static android.util.Range<Integer> recordingRange(CameraManager cm, String id, int fps) throws Exception {
        android.util.Range<Integer>[] ranges = cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) for (android.util.Range<Integer> range : ranges) {
            if (VideoPolicy.fixedRate(range.getLower(), range.getUpper(), fps)) return range;
        }
        return null;
    }

    /** User-requested quality/rate, preserving the sensor field. 4K requires
     * a genuine 3840-wide native-aspect output; no upscale or silent 16:9 crop. */
    static Size videoProfile(CameraManager cm, String id, int quality, int fps) throws Exception {
        if (!VideoPolicy.allowedRate(quality, fps) || recordingRange(cm, id, fps) == null) return null;
        CameraCharacteristics chars = cm.getCameraCharacteristics(id);
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (map == null || active == null) return null;
        // The repeating request drives both recorder and YUV preview targets.
        int[] preview = previewConfig(cm, id, fps >= 60);
        if (preview.length != 4) return null;
        long previewDuration = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, new Size(preview[0], preview[1]));
        if (previewDuration <= 0 || previewDuration > 1_000_000_000L / fps + 1) return null;
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (sizes == null) return null;
        for (Size size : sizes) {
            if (!VideoPolicy.matchesQuality(size.getWidth(), size.getHeight(), quality) ||
                !VideoPolicy.matchesSensor(size.getWidth(), size.getHeight(), active.width(), active.height())) continue;
            long duration = map.getOutputMinFrameDuration(MediaRecorder.class, size);
            if (duration <= 0 || duration > 1_000_000_000L / fps + 1) continue;
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!codec.isEncoder() || !codec.isHardwareAccelerated()) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                    boolean surface = false;
                    for (int format : caps.colorFormats) if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                    if (surface && caps.getVideoCapabilities() != null &&
                        caps.getVideoCapabilities().areSizeAndRateSupported(size.getWidth(), size.getHeight(), fps)) return size;
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }

    /** Largest advertised native-aspect recorder stream supported by hardware AVC
     * at 30 fps. Not unbinned sensor RAW, and not proof of combined-session throughput.
     * Never silently substitutes a wide crop when a native stream is unavailable.
     */
    static Size videoSize(CameraManager cm, String id) throws Exception {
        CameraCharacteristics chars = cm.getCameraCharacteristics(id);
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Rect active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (map == null || active == null) return null;
        android.util.Range<Integer>[] fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        boolean thirtyFps = false;
        if (fps != null) for (android.util.Range<Integer> range : fps) {
            if (range.getUpper() == VideoPolicy.FPS && range.getLower() <= VideoPolicy.FPS) thirtyFps = true;
        }
        if (!thirtyFps) return null;
        Size result = null;
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        if (sizes != null) for (Size size : sizes) {
            if (!VideoPolicy.matchesSensor(size.getWidth(), size.getHeight(), active.width(), active.height())) continue;
            long duration = map.getOutputMinFrameDuration(MediaRecorder.class, size);
            if (duration > 1_000_000_000L / VideoPolicy.FPS + 1) continue;
            boolean encodable = false;
            for (MediaCodecInfo codec : codecs) {
                if (!codec.isEncoder() || !codec.isHardwareAccelerated()) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                    boolean surfaceInput = false;
                    for (int format : caps.colorFormats) if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surfaceInput = true;
                    MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                    if (surfaceInput && video != null && video.areSizeAndRateSupported(size.getWidth(), size.getHeight(), VideoPolicy.FPS)) {
                        encodable = true; break;
                    }
                } catch (IllegalArgumentException ignored) { /* Another encoder may support AVC. */ }
            }
            if (encodable && (result == null || (long) size.getWidth() * size.getHeight() > (long) result.getWidth() * result.getHeight())) result = size;
        }
        return result;
    }

    private static boolean hasCapability(CameraCharacteristics c, int capability) {
        int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities != null) for (int value : capabilities) if (value == capability) return true;
        return false;
    }

    private static void appendMetadata(StringBuilder out, String label, CameraCharacteristics c, CameraCharacteristics.Key<?> key) {
        out.append(label).append(": ");
        try {
            Object value = c.get(key);
            out.append(value == null ? "not exposed" : value instanceof int[] ? Arrays.toString((int[]) value) :
                    value instanceof float[] ? Arrays.toString((float[]) value) : String.valueOf(value));
        } catch (Exception e) { out.append("unavailable(").append(e.getClass().getSimpleName()).append(')'); }
        out.append('\n');
    }

    /** Returns false on query failure only for deciding whether to query dependent metadata. */
    private static boolean appendCapability(StringBuilder out, String label, CameraCharacteristics c, int capability) {
        out.append(label).append(": ");
        try {
            boolean present = hasCapability(c, capability);
            out.append(present).append('\n');
            return present;
        } catch (Exception e) {
            out.append("unavailable(").append(e.getClass().getSimpleName()).append(")\n");
            return false;
        }
    }

    /** Diagnostics only: a zero minimum duration means unknown, not unlimited FPS. */
    private static void appendRawSizes(StringBuilder out, StreamConfigurationMap map, int format, Size[] sizes) {
        if (sizes == null || sizes.length == 0) { out.append("none advertised\n"); return; }
        out.append('\n');
        for (Size size : sizes) {
            out.append("  ").append(size);
            try {
                long duration = map.getOutputMinFrameDuration(format, size);
                out.append(" minFrameNs=").append(duration);
                if (duration > 0) out.append(" theoreticalMaxFps=")
                        .append(String.format(java.util.Locale.US, "%.2f", 1_000_000_000.0 / duration));
                else out.append(" theoreticalMaxFps=unknown");
            } catch (Exception e) { out.append(" minFrameNs=unavailable(").append(e.getClass().getSimpleName()).append(')'); }
            try { out.append(" stallNs=").append(map.getOutputStallDuration(format, size)); }
            catch (Exception e) { out.append(" stallNs=unavailable(").append(e.getClass().getSimpleName()).append(')'); }
            if (format == ImageFormat.RAW_SENSOR) {
                // RAW16 storage estimate, NOT ADC precision or measured throughput.
                long bytes = (long) size.getWidth() * size.getHeight() * 2L;
                out.append(" tightPackedRAW16BytesPerFrame=").append(bytes)
                        .append(" excludingRowPadding");
            }
            out.append('\n');
        }
    }

    private static void appendRawMap(StringBuilder out, String label, StreamConfigurationMap map) {
        out.append("RAW stream map: ").append(label).append(" (metadata only)\n");
        if (map == null) { out.append("  map not exposed\n"); return; }
        try { out.append("  Output format IDs: ").append(Arrays.toString(map.getOutputFormats())).append('\n'); }
        catch (Exception e) { out.append("Output format query unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
        for (int format : new int[]{ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12}) {
            String name = format == ImageFormat.RAW_SENSOR ? "RAW_SENSOR" : format == ImageFormat.RAW10 ? "RAW10" : "RAW12";
            out.append("  ").append(name).append(" (format ").append(format).append(") sizes/minFrameNs: ");
            try { appendRawSizes(out, map, format, map.getOutputSizes(format)); }
            catch (Exception e) { out.append("unavailable(").append(e.getClass().getSimpleName()).append(")\n"); }
            out.append("  High-resolution/slow ").append(name).append(": ");
            try { appendRawSizes(out, map, format, map.getHighResolutionOutputSizes(format)); }
            catch (Exception e) { out.append("unavailable(").append(e.getClass().getSimpleName()).append(")\n"); }
        }
    }

    private static void appendSensorMetadata(StringBuilder out, CameraCharacteristics c) {
        // Keep each optional calibration/control key independent of all other queries.
        appendMetadata(out, "Sensor sensitivity range", c, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        appendMetadata(out, "Max analog ISO", c, CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        appendMetadata(out, "Exposure time range (ns)", c, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        appendMetadata(out, "Max frame duration (ns)", c, CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        appendMetadata(out, "Sensor CFA enum", c, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        appendMetadata(out, "Black levels", c, CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        appendMetadata(out, "White level (signal level, not storage bit depth)", c, CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        appendMetadata(out, "RAW color transform 1", c, CameraCharacteristics.SENSOR_COLOR_TRANSFORM1);
        appendMetadata(out, "RAW calibration transform 1", c, CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1);
        appendMetadata(out, "RAW forward matrix 1", c, CameraCharacteristics.SENSOR_FORWARD_MATRIX1);
        appendMetadata(out, "RAW reference illuminant 1", c, CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        appendMetadata(out, "RAW color transform 2", c, CameraCharacteristics.SENSOR_COLOR_TRANSFORM2);
        appendMetadata(out, "RAW calibration transform 2", c, CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2);
        appendMetadata(out, "RAW forward matrix 2", c, CameraCharacteristics.SENSOR_FORWARD_MATRIX2);
        appendMetadata(out, "RAW reference illuminant 2", c, CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        appendCapability(out, "Manual sensor capability", c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        appendCapability(out, "Manual post-processing capability", c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING);
        appendMetadata(out, "AE modes", c, CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        appendMetadata(out, "AWB modes", c, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        appendMetadata(out, "AF modes", c, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        appendMetadata(out, "OIS modes", c, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        appendMetadata(out, "EIS modes", c, CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        out.append("Stabilization: advertised modes, not measured crop or session support.\n");
    }

    private static void appendFootageCapabilities(StringBuilder out, CameraCharacteristics c, StreamConfigurationMap map) {
        appendCapability(out, "RAW capability", c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW);
        appendSensorMetadata(out, c);
        appendRawMap(out, "DEFAULT pixel mode", map);
        if (Build.VERSION.SDK_INT >= 31) {
            appendCapability(out, "Ultra-high-resolution sensor capability", c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR);
            appendMetadata(out, "Maximum pixel array", c, CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
            try { appendRawMap(out, "MAXIMUM_RESOLUTION pixel mode",
                    c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)); }
            catch (Exception e) { out.append("Maximum-resolution RAW map unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
        }
        if (map != null) {
            try { out.append("Recorder sizes: ").append(Arrays.toString(map.getOutputSizes(MediaRecorder.class))).append('\n'); }
            catch (Exception e) { out.append("Recorder sizes unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            try { out.append("HEIC sizes: ").append(Arrays.toString(map.getOutputSizes(ImageFormat.HEIC)))
                    .append(" (metadata, not a verified HEIC encoder/session)\n"); }
            catch (Exception e) { out.append("HEIC sizes unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            if (appendCapability(out, "Constrained high-speed capability", c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                try {
                    for (Size size : map.getHighSpeedVideoSizes()) {
                        try { out.append("Constrained high-speed ").append(size).append(" FPS ranges=")
                                .append(Arrays.toString(map.getHighSpeedVideoFpsRangesFor(size)))
                                .append(" (not a regular/full-field/RAW recording guarantee)\n"); }
                        catch (Exception e) { out.append("High-speed FPS query unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
                    }
                } catch (Exception e) { out.append("High-speed metadata unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            }
        }
        appendMetadata(out, "Tone-map modes", c, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
        if (Build.VERSION.SDK_INT >= 31) appendMetadata(out, "Rotate-and-crop modes (NONE requested when advertised)", c, CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES);
        if (Build.VERSION.SDK_INT >= 33) {
            appendCapability(out, "10-bit dynamic range capability", c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT);
            try {
                DynamicRangeProfiles profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
                if (profiles != null) for (long profile : profiles.getSupportedProfiles()) {
                    String name = profile == DynamicRangeProfiles.STANDARD ? "SDR" : profile == DynamicRangeProfiles.HLG10 ? "HLG10" :
                            profile == DynamicRangeProfiles.HDR10 ? "HDR10" : profile == DynamicRangeProfiles.HDR10_PLUS ? "HDR10+" : "Other HDR " + profile;
                    try { out.append("Dynamic range ").append(name).append(" constraints=").append(profiles.getProfileCaptureRequestConstraints(profile)).append('\n'); }
                    catch (Exception e) { out.append("Dynamic range constraint unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
                }
            } catch (Exception e) { out.append("Dynamic range profiles unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            try { if (map != null) out.append("P010 sizes: ").append(Arrays.toString(map.getOutputSizes(ImageFormat.YCBCR_P010))).append('\n'); }
            catch (Exception e) { out.append("P010 sizes unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
        }
        out.append("RAW metadata/stream availability is not proof of RAW video throughput. HLG/HDR and flat 8-bit curves are not camera Log.\n");
    }

    private static void appendEncoderCapabilities(StringBuilder out) {
        try {
            out.append("\nHardware video encoders (availability, not a recording mode already implemented):\n");
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!codec.isEncoder() || !codec.isHardwareAccelerated()) continue;
                for (String mime : codec.getSupportedTypes()) {
                    if (!MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime) && !MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime)) continue;
                    try {
                        MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(mime);
                        out.append(codec.getName()).append(' ').append(mime).append(" profiles: ");
                        boolean main10 = false;
                        for (MediaCodecInfo.CodecProfileLevel profile : caps.profileLevels) {
                            out.append(profile.profile).append('/').append(profile.level).append(' ');
                            if (profile.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                    profile.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                    profile.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) main10 = true;
                        }
                        if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime)) out.append(" HEVC Main10=").append(main10);
                        out.append(" colorFormats=").append(Arrays.toString(caps.colorFormats));
                        out.append(" surfaceInput=");
                        boolean surfaceInput = false;
                        for (int format : caps.colorFormats) if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surfaceInput = true;
                        out.append(surfaceInput);
                        MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                        if (video != null) out.append(" widths=").append(video.getSupportedWidths()).append(" heights=").append(video.getSupportedHeights()).append(" bitrates=").append(video.getBitrateRange());
                        if (video != null) out.append(" alignment=").append(video.getWidthAlignment()).append('x')
                                .append(video.getHeightAlignment()).append(" frameRateRange=").append(video.getSupportedFrameRates());
                        out.append('\n');
                    } catch (Exception e) { out.append(codec.getName()).append(' ').append(mime)
                            .append(" capability query unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
                }
            }
        } catch (Exception e) { out.append("Encoder capability query failed: ").append(e).append('\n'); }
    }

    static String report(CameraManager cm) throws Exception {
        StringBuilder out = new StringBuilder("NotiCam Camera2 capability report\nDevice: ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" / ").append(Build.FINGERPRINT).append('\n');
        try {
            Target chosen = discover(cm);
            out.append("Selected 50 MP: ").append(chosen == null ? "not exposed by queried regular-session paths" :
                    chosen.cameraId + " " + chosen.size + " pixelMode=" + chosen.pixelMode + " source=" + chosen.source).append('\n');
        } catch (Exception e) { out.append("50 MP discovery unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
        out.append("Enumerated IDs: ").append(Arrays.toString(cm.getCameraIdList()))
                .append("\nQueried IDs (includes existing auxiliary routes): ").append(candidateIds(cm))
                .append("\nMetadata eligibility is not proof of a successful capture session.\n")
                .append("Format legend: HAL RAW_SENSOR/RAW16=32 (0x20), BLOB=33 (0x21; commonly JPEG), PRIVATE=34 (0x22), YUV_420_888=35 (0x23), RAW10=37, RAW12=38; Java JPEG=256 (0x100).\n")
                .append("Vendor tables require schema verification: 33/35 entries do not prove RAW, and a final 0/1 field has no assumed meaning in this diagnostic.\n")
                .append("Read-only report: no streams opened, no capture, benchmark or system-setting changes. FPS limits are theoretical metadata, not measured delivery.\n");
        appendEncoderCapabilities(out); // Preserve codec evidence even if vendor tables truncate later.
        for (String id : candidateIds(cm)) try {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            out.append("\nCamera ").append(id).append(" facing=").append(c.get(CameraCharacteristics.LENS_FACING))
                    .append(" physical IDs=").append(c.getPhysicalCameraIds()).append('\n');
            for (String physical : c.getPhysicalCameraIds()) try {
                CameraCharacteristics child = cm.getCameraCharacteristics(physical);
                StreamConfigurationMap childMap = child.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                out.append("Physical ").append(physical).append(" JPEG: ")
                    .append(childMap == null ? "not exposed" : Arrays.toString(childMap.getOutputSizes(ImageFormat.JPEG))).append('\n');
                if (Build.VERSION.SDK_INT >= 31) {
                    StreamConfigurationMap childMax = child.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                    if (childMax != null) out.append("Physical maximum JPEG: ").append(Arrays.toString(childMax.getOutputSizes(ImageFormat.JPEG))).append('\n');
                }
            } catch (Exception e) { out.append("Physical ").append(physical).append(": not queryable\n"); }
            StreamConfigurationMap map = null;
            try { map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP); }
            catch (Exception e) { out.append("Default stream map unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            appendMetadata(out, "Active array", c, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            appendMetadata(out, "Sensor pixels", c, CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            appendMetadata(out, "Physical size", c, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            appendMetadata(out, "Focal lengths", c, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            appendFootageCapabilities(out, c, map);
            try { out.append("Native-aspect AVC/30 recorder candidate: ").append(videoSize(cm, id)).append('\n'); }
            catch (Exception e) { out.append("Recorder candidate unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            try { out.append("Preview profile (width,height,minFPS,maxFPS): ").append(Arrays.toString(previewConfig(cm, id, true))).append('\n'); }
            catch (Exception e) { out.append("Preview profile unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            if (map != null) {
                try { out.append("YUV: ").append(Arrays.toString(map.getOutputSizes(ImageFormat.YUV_420_888))).append('\n'); }
                catch (Exception e) { out.append("YUV metadata unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
                try { out.append("JPEG: ").append(Arrays.toString(map.getOutputSizes(ImageFormat.JPEG))).append('\n'); }
                catch (Exception e) { out.append("JPEG metadata unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
                try { out.append("Slow JPEG: ").append(Arrays.toString(map.getHighResolutionOutputSizes(ImageFormat.JPEG))).append('\n'); }
                catch (Exception e) { out.append("Slow JPEG metadata unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            }
            try {
                out.append("Request keys: ");
                for (CaptureRequest.Key<?> key : c.getAvailableCaptureRequestKeys()) out.append(key.getName()).append(", ");
                out.append('\n');
            } catch (Exception e) { out.append("Request keys unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            try { out.append("Session keys: ").append(c.getAvailableSessionKeys()).append('\n'); }
            catch (Exception e) { out.append("Session keys unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
            if (Build.VERSION.SDK_INT >= 31) {
                appendMetadata(out, "Maximum array", c, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION);
                try {
                    StreamConfigurationMap max = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                    if (max != null) out.append("Maximum JPEG: ").append(Arrays.toString(max.getOutputSizes(ImageFormat.JPEG))).append('\n');
                } catch (Exception e) { out.append("Maximum JPEG metadata unavailable: ").append(e.getClass().getSimpleName()).append('\n'); }
                try {
                    CameraExtensionCharacteristics ext = cm.getCameraExtensionCharacteristics(id);
                    for (int extension : ext.getSupportedExtensions()) out.append("Extension ").append(extension).append(" JPEG (requires extension session): ")
                            .append(ext.getExtensionSupportedSizes(extension, ImageFormat.JPEG)).append('\n');
                } catch (Exception e) { out.append("Extensions: ").append(e.getClass().getSimpleName()).append('\n'); }
            }
            for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                String name = key.getName().toLowerCase(java.util.Locale.US);
                if (name.startsWith("android.") || !(name.contains("stream") || name.contains("resol") || name.contains("remosaic") || name.contains("pixel") || name.contains("log") || name.contains("raw") || name.contains("hdr") || name.contains("bitdepth"))) continue;
                try {
                    Object value = c.get(key);
                    String text = value instanceof int[] ? Arrays.toString((int[]) value) : value instanceof long[] ? Arrays.toString((long[]) value) : String.valueOf(value);
                    out.append(key.getName()).append('=').append(text.substring(0, Math.min(3000, text.length()))).append('\n');
                } catch (Exception e) { out.append(key.getName()).append(": unreadable\n"); }
                if (out.length() > 150_000) return out.append("\nReport truncated").toString();
            }
        } catch (Exception e) { out.append("Camera ").append(id).append(": ").append(e).append('\n'); }
        String text = out.toString();
        return text.length() > 150_000 ? text.substring(0, 150_000) + "\nReport truncated" : text;
    }
}
