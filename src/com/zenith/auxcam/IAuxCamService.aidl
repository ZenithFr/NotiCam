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
}
