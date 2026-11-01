package com.zenith.auxcam;

/** One global zoom ladder, one physical-lens-relative crop. No Android dependencies. */
public final class CameraZoom {
    private CameraZoom() {}
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
    public static float digitalRatio(float globalZoom, String physicalId, float maxDigitalZoom) {
        float base = "3".equals(physicalId) ? 2f : "2".equals(physicalId) ? .6f : 1f;
        float global = Float.isFinite(globalZoom) ? globalZoom : base;
        float max = Float.isFinite(maxDigitalZoom) ? Math.max(1f, maxDigitalZoom) : 1f;
        return Math.max(1f, Math.min(max, global / base));
    }
}
