package com.zenith.auxcam;

import android.os.ParcelFileDescriptor;
import android.view.Surface;
import com.zenith.auxcam.IAuxCamCallback;

interface IAuxCamService {
    void setCallback(IAuxCamCallback cb);
    boolean openStereoSession(
        in Surface colorSurface, int colorW, int colorH,
        in Surface depthSurface, int depthW, int depthH,
        in Surface stillSurface, int stillW, int stillH, int stillFormat,
        String colorPhysicalId, in Surface recordingSurface, boolean videoMode, float requestedZoom, int previewMaxFps);

    /** width, height, pixel mode; empty when not exposed. */
    int[] getHighResolutionJpegSize(String lensId);
    int[] getStandardJpegSize(String lensId);
    int[] getRawSensorSize(String lensId);
    /** width, height, sensor orientation for the selected recorder stream. */
    int[] getVideoSize(String lensId);
    /** width, height, min FPS, max FPS; full-field 4:3 preview independent of still size. */
    int[] getPreviewConfiguration(String lensId, boolean bokeh, boolean highResolution, boolean prefer60);
    String getCameraDiagnostics();

    void triggerStill(long shutterCommandNs, boolean highQualityNr);
    void triggerStillWithZsl(long shutterCommandNs, boolean highQualityNr, boolean enableZsl);
    void triggerStillWithDng(long shutterCommandNs, in ParcelFileDescriptor dngPfd);
    void closeSession();

    // ── Live camera controls (Apple Camera chrome) ──────────────────────────
    /** 0 = off, 1 = auto, 2 = on (fires on the still request). */
    void setFlashMode(int mode);
    /** Exposure compensation in AE steps (clamped to the sensor range). */
    void setEvCompensation(int steps);
    /** Global zoom; one physical-lens-relative crop for preview + still. */
    void setZoom(float ratio);
    /** Tap-to-focus at normalised sensor coordinates (0..1, landscape sensor). */
    void focusAt(float sensorNormX, float sensorNormY);

    /** Lock or unlock AE and AF at current point. */
    void setAfLock(boolean locked);
    /** Set manual focus distance (0.0 = infinity, 1.0 = closest / macro). */
    void setManualFocus(float distanceFraction);
    /** Reset focus back to continuous autofocus. */
    void resetFocus();
}
