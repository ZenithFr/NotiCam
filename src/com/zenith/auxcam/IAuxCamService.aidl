package com.zenith.auxcam;

import android.view.Surface;
import com.zenith.auxcam.IAuxCamCallback;

interface IAuxCamService {
    void setCallback(IAuxCamCallback cb);
    boolean openStereoSession(
        in Surface colorSurface, int colorW, int colorH,
        in Surface depthSurface, int depthW, int depthH,
        in Surface stillSurface, int stillW, int stillH,
        String colorPhysicalId);

    void triggerStill(long shutterCommandNs, boolean highQualityNr);
    void triggerStillWithZsl(long shutterCommandNs, boolean highQualityNr, boolean enableZsl);
    void closeSession();

    // ── Live camera controls (Apple Camera chrome) ──────────────────────────
    /** 0 = off, 1 = auto, 2 = on (fires on the still request). */
    void setFlashMode(int mode);
    /** Exposure compensation in AE steps (clamped to the sensor range). */
    void setEvCompensation(int steps);
    /** Digital zoom, 1.0 = 1x. Applied as SCALER_CROP_REGION to preview + still. */
    void setZoom(float ratio);
    /** Tap-to-focus at normalised sensor coordinates (0..1, landscape sensor). */
    void focusAt(float sensorNormX, float sensorNormY);
    /** Re-opens the camera pinned to another physical sensor ("0" = auto). */
    void setPhysicalLens(String physicalId);

    /** Lock or unlock AE and AF at current point. */
    void setAfLock(boolean locked);
    /** Set manual focus distance (0.0 = infinity, 1.0 = closest / macro). */
    void setManualFocus(float distanceFraction);
    /** Reset focus back to continuous autofocus. */
    void resetFocus();
}
