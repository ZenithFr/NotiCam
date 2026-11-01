package com.zenith.auxcam;

interface IAuxCamCallback {
    void onSessionReady(String cameraId);
    void onSessionError(String msg);
    void onStillCaptured();
    void onStats(float colorFps, float depthFps, float colorDepthDtMs_p99);
}
