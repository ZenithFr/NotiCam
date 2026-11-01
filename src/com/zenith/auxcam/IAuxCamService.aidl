package com.zenith.auxcam;

import android.view.Surface;
import com.zenith.auxcam.IAuxCamCallback;

interface IAuxCamService {
    void setCallback(IAuxCamCallback cb) = 0;
    boolean openStereoSession(
        in Surface colorSurface, int colorW, int colorH,
        in Surface depthSurface, int depthW, int depthH,
        in Surface stillSurface, int stillW, int stillH, int stillFormat,
        String colorPhysicalId, in Surface recordingSurface, boolean videoMode, float requestedZoom, int previewMaxFps) = 1;

    /** width, height, pixel mode; empty when not exposed. */
    int[] getHighResolutionJpegSize() = 2;
    int[] getStandardJpegSize(String lensId) = 3;
    /** width, height, sensor orientation for the selected recorder stream. */
    int[] getVideoSize(String lensId) = 4;
    /** width, height, min FPS, max FPS; full-field 4:3 preview independent of still size. */
    int[] getPreviewConfiguration(String lensId, boolean bokeh, boolean highResolution, boolean prefer60) = 5;
    String getCameraDiagnostics() = 6;

    void triggerStill(long shutterCommandNs, boolean highQualityNr) = 7;
    void triggerStillWithZsl(long shutterCommandNs, boolean highQualityNr, boolean enableZsl) = 8;
    void closeSession() = 9;

    // ── Live camera controls (Apple Camera chrome) ──────────────────────────
    /** 0 = off, 1 = auto, 2 = on (fires on the still request). */
    void setFlashMode(int mode) = 10;
    /** Exposure compensation in AE steps (clamped to the sensor range). */
    void setEvCompensation(int steps) = 11;
    /** Global zoom; one physical-lens-relative crop for preview + still. */
    void setZoom(float ratio) = 12;
    /** Tap-to-focus at normalised sensor coordinates (0..1, landscape sensor). */
    void focusAt(float sensorNormX, float sensorNormY) = 13;

    /** Lock or unlock AE and AF at current point. */
    void setAfLock(boolean locked) = 14;
    /** Set manual focus distance (0.0 = infinity, 1.0 = closest / macro). */
    void setManualFocus(float distanceFraction) = 15;
    /** Reset focus back to continuous autofocus. */
    void resetFocus() = 16;
    /** Reserved Shizuku removal transaction. Closes camera and exits only this sidecar. */
    void destroy() = 16777114;
}
