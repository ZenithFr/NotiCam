package com.zenith.auxcam;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
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
        return discover(cm, "0");
    }

    static Target discover(CameraManager cm, String preferredLensId) throws Exception {
        String targetId = preferredLensId != null ? preferredLensId : "0";
        try {
            CameraCharacteristics c = cm.getCameraCharacteristics(targetId);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                Size s = jpegSize(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), true);
                if (s != null) return new Target(targetId, s, CaptureRequest.SENSOR_PIXEL_MODE_DEFAULT, "Camera2 default/high-resolution map");
                if (Build.VERSION.SDK_INT >= 31 && c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION) != null) {
                    s = jpegSize(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION), true);
                    if (s != null) return new Target(targetId, s, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, "Camera2 maximum-resolution map");
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
                                return new Target(targetId, new Size(w, h), CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, "Nothing camera 50 MP stream map");
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Exception ignored) { /* Not supported on this lens */ }
        return null;
    }

    static Size rawSize(CameraManager cm, String lensId) {
        try {
            String id = "2".equals(lensId) || "3".equals(lensId) ? lensId : "0";
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
                if (sizes != null && sizes.length > 0) {
                    Size best = sizes[0];
                    for (Size s : sizes) {
                        if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                            best = s;
                        }
                    }
                    return best;
                }
            }
        } catch (Throwable ignored) {}
        return null;
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
    static Size videoSize(CameraManager cm, String id) throws Exception {
        StreamConfigurationMap map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        android.util.Range<Integer>[] fps = cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        boolean thirtyFps = false;
        if (fps != null) for (android.util.Range<Integer> range : fps) {
            if (range.getUpper() == 30 && range.getLower() <= 30) thirtyFps = true;
        }
        if (!thirtyFps) return null;
        Size result = null;
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (sizes != null) for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080 || Math.abs((double) s.getWidth() / s.getHeight() - 16.0 / 9.0) > .02) continue;
            long duration = map.getOutputMinFrameDuration(MediaRecorder.class, s);
            if (duration > 33_333_334L) continue;
            if (result == null || (long) s.getWidth() * s.getHeight() > (long) result.getWidth() * result.getHeight()) result = s;
        }
        return result;
    }
    static String report(CameraManager cm) throws Exception {
        StringBuilder out = new StringBuilder("NotiCam Camera2 capability report\nDevice: ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" / ").append(Build.FINGERPRINT).append('\n');
        Target chosen = discover(cm);
        out.append("Selected 50 MP: ").append(chosen == null ? "not exposed by queried regular-session paths" :
                chosen.cameraId + " " + chosen.size + " pixelMode=" + chosen.pixelMode + " source=" + chosen.source).append('\n');
        out.append("Enumerated IDs: ").append(Arrays.toString(cm.getCameraIdList()))
                .append("\nQueried IDs (includes existing auxiliary routes): ").append(candidateIds(cm))
                .append("\nMetadata eligibility is not proof of a successful capture session.\n");
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
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            out.append("Active array: ").append(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
                    .append(" sensor pixels=").append(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE))
                    .append(" physical size=").append(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
                    .append(" focal lengths=").append(Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))).append('\n');
            out.append("Preview profile (width,height,minFPS,maxFPS): ").append(Arrays.toString(previewConfig(cm, id, true))).append('\n');
            if (map != null) out.append("YUV: ").append(Arrays.toString(map.getOutputSizes(ImageFormat.YUV_420_888)))
                    .append("\nRAW: ").append(Arrays.toString(map.getOutputSizes(ImageFormat.RAW_SENSOR))).append('\n');
            out.append("Request keys: ");
            for (CaptureRequest.Key<?> key : c.getAvailableCaptureRequestKeys()) out.append(key.getName()).append(", ");
            out.append("\nSession keys: ").append(c.getAvailableSessionKeys()).append('\n');
            if (map != null) out.append("JPEG: ").append(Arrays.toString(map.getOutputSizes(ImageFormat.JPEG)))
                    .append("\nSlow JPEG: ").append(Arrays.toString(map.getHighResolutionOutputSizes(ImageFormat.JPEG))).append('\n');
            if (Build.VERSION.SDK_INT >= 31) {
                StreamConfigurationMap max = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                out.append("Maximum array: ").append(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)).append('\n');
                if (max != null) out.append("Maximum JPEG: ").append(Arrays.toString(max.getOutputSizes(ImageFormat.JPEG))).append('\n');
                try {
                    CameraExtensionCharacteristics ext = cm.getCameraExtensionCharacteristics(id);
                    for (int extension : ext.getSupportedExtensions()) out.append("Extension ").append(extension).append(" JPEG (requires extension session): ")
                            .append(ext.getExtensionSupportedSizes(extension, ImageFormat.JPEG)).append('\n');
                } catch (Exception e) { out.append("Extensions: ").append(e.getClass().getSimpleName()).append('\n'); }
            }
            for (CameraCharacteristics.Key<?> key : c.getKeys()) {
                String name = key.getName().toLowerCase(java.util.Locale.US);
                if (name.startsWith("android.") || !(name.contains("stream") || name.contains("resol") || name.contains("remosaic") || name.contains("pixel"))) continue;
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
