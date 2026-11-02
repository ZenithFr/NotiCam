package com.zenith.auxcam;

interface IAuxCamCallback {
    void onSessionReady(String cameraId);
    void onSessionError(String msg);
    void onStillCaptured();
    void onStats(float colorFps, float depthFps, float colorDepthDtMs_p99);
    /** Active selection: manual flag, ISO, exposure ns, AWB mode. Error null on acceptance. */
    void onSensorControlsResult(int token, in long[] selection, String error);
    /** Success only after all requested DNG writes finish. Error is null on success. */
    void onRawCaptureFinished(int token, int savedCount, String error);
}
