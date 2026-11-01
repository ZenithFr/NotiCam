package com.zenith.auxcam;

/** One global zoom ladder, one physical-lens-relative crop. No Android dependencies. */
public final class CameraZoom {
    private CameraZoom() {}
    /** Stereo session 5 uses the main lens, not a new UI/zoom lens. */
    public static String lensForSession(String cameraId) {
        return cameraId == null || "5".equals(cameraId) ? "0" : cameraId;
    }
    public static String lensForZoom(float zoom) {
        if (!Float.isFinite(zoom)) return "0";
        if (zoom < 1f) return "2";
        return zoom >= 2f ? "3" : "0";
    }
    /** Frame-rate-independent, retargetable crop easing within ONE physical camera.
     * Never selects a lens; still capture snaps independently to the requested zoom.
     */
    public static float smoothPreviewZoom(float current, float target, long elapsedMs) {
        if (!Float.isFinite(target) || target <= 0f) return Float.isFinite(current) && current > 0f ? current : 1f;
        if (!Float.isFinite(current) || current <= 0f) return target;
        double remaining = Math.log((double) target / current);
        if (Math.abs(remaining) < .0015) return target;
        double dt = Math.max(1L, Math.min(32L, elapsedMs));
        return current * (float) Math.exp(remaining * (1.0 - Math.exp(-dt / 55.0)));
    }
    /** Match the incoming wider live image to the frozen outgoing field of view,
     * then return it to identity. The old snapshot and live image share progress.
     */
    public static float incomingPreviewScale(float ratio, float progress, float snapshotStartScale) {
        float r = Float.isFinite(ratio) && ratio > 0f ? Math.max(.3f, Math.min(3.4f, ratio)) : 1f;
        float p = Float.isFinite(progress) ? Math.max(0f, Math.min(1f, progress)) : 0f;
        float start = Float.isFinite(snapshotStartScale) ? Math.max(1f, Math.min(3.4f, snapshotStartScale)) : 1f;
        if (r >= 1f) return 1f;
        if (p == 1f) return 1f; // Exactly identity before removing the layer.
        return start / r * (1f - p) + p;
    }
    public static float outgoingSnapshotScale(float ratio, float progress, float snapshotStartScale) {
        float r = Float.isFinite(ratio) && ratio > 0f ? Math.max(.3f, Math.min(3.4f, ratio)) : 1f;
        return r * incomingPreviewScale(r, progress, snapshotStartScale);
    }
    public static float digitalRatio(float globalZoom, String physicalId, float maxDigitalZoom) {
        float base = "3".equals(physicalId) ? 2f : "2".equals(physicalId) ? .6f : 1f;
        float global = Float.isFinite(globalZoom) ? globalZoom : base;
        float max = Float.isFinite(maxDigitalZoom) ? Math.max(1f, maxDigitalZoom) : 1f;
        return Math.max(1f, Math.min(max, global / base));
    }
}
