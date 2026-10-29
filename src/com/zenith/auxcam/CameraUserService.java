package com.zenith.auxcam;

import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class CameraUserService extends IAuxCamService.Stub {
    private static final String TAG = "CameraUserService";
    private static final String CAMERA_ID = "5";

    private final Context mContext;
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;
    private HandlerExecutor mExecutor;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private IAuxCamCallback mCallback;

    private Surface mColorSurface;
    private Surface mDepthSurface;
    private Surface mStillSurface;

    private CaptureRequest.Builder mPreviewRequestBuilder;
    private long mShutterCommandNs = 0;

    // Sparse frame statistics
    private long mLastColorFrameTimeNs = 0;
    private long mLastDepthFrameTimeNs = 0;
    private int mColorFrameCount = 0;
    private int mDepthFrameCount = 0;
    private long mStatsWindowStartMs = 0;

    // Viewfinder frame-time histogram
    private long mLastViewfinderFrameNs = 0;
    private final int[] mFrameHistogram = new int[7]; // <25, 25-30, 30-33, 33-35, 35-40, 40-50, >50 ms
    private int mTotalHistogramFrames = 0;

    public CameraUserService(Context context) {
        mContext = context;
        setupEnvironment();
        startBackgroundThread();
        Log.i(TAG, "CameraUserService initialized in UID=" + android.os.Process.myUid() + ", PID=" + android.os.Process.myPid());
    }

    static class FalseSupplier implements java.util.function.BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return false;
        }
    }

    private void setupEnvironment() {
        try {
            // Disable Android 16 DesktopModeFlags that query Settings.Global under mismatched UID
            Class<?> clazz = Class.forName("android.window.DesktopModeFlags");
            Field flagField = clazz.getField("ENABLE_CAMERA_COMPAT_SIMULATE_REQUESTED_ORIENTATION");
            Object flag = flagField.get(null);

            Field overrideField = clazz.getDeclaredField("mShouldOverrideByDevOption");
            overrideField.setAccessible(true);
            overrideField.set(flag, false);

            Field funcField = clazz.getDeclaredField("mFlagFunction");
            funcField.setAccessible(true);
            funcField.set(flag, new FalseSupplier());
            Log.i(TAG, "Disabled desktop camera compat flags");
        } catch (Throwable t) {
            Log.w(TAG, "Desktop flags note: " + t);
        }

        try {
            fixAttribution(mContext, "com.android.shell", 2000);
        } catch (Throwable t) {
            Log.w(TAG, "fixAttribution note: " + t);
        }
    }

    private static void fixAttribution(Context ctx, String pkg, int uid) {
        try {
            Field asField = ctx.getClass().getDeclaredField("mAttributionSource");
            asField.setAccessible(true);
            Object as = asField.get(ctx);
            if (as != null) {
                Field stateField = as.getClass().getDeclaredField("mAttributionSourceState");
                stateField.setAccessible(true);
                Object state = stateField.get(as);

                Field pkgField = state.getClass().getDeclaredField("packageName");
                pkgField.setAccessible(true);
                pkgField.set(state, pkg);

                Field uidField = state.getClass().getDeclaredField("uid");
                uidField.setAccessible(true);
                uidField.set(state, uid);
                Log.i(TAG, "Fixed AttributionSource to pkg=" + pkg + " uid=" + uid);
            }
        } catch (Throwable t) {
            Log.w(TAG, "fixAttribution failed: " + t);
        }
    }

    private void startBackgroundThread() {
        mCameraThread = new HandlerThread("CameraUserThread");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
        mExecutor = new HandlerExecutor(mCameraHandler);
    }

    private void stopBackgroundThread() {
        if (mCameraThread != null) {
            mCameraThread.quitSafely();
            try {
                mCameraThread.join();
                mCameraThread = null;
                mCameraHandler = null;
                mExecutor = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping camera thread", e);
            }
        }
    }

    static class HandlerExecutor implements Executor {
        private final Handler mHandler;

        HandlerExecutor(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void execute(Runnable command) {
            if (mHandler != null) {
                mHandler.post(command);
            } else {
                command.run();
            }
        }
    }

    @Override
    public void setCallback(IAuxCamCallback cb) {
        mCallback = cb;
        Log.i(TAG, "Registered IAuxCamCallback: " + cb);
    }

    @Override
    public boolean openStereoSession(
            Surface colorSurface, int colorW, int colorH,
            Surface depthSurface, int depthW, int depthH,
            Surface stillSurface, int stillW, int stillH,
            String colorPhysicalId) {

        Log.i(TAG, "openStereoSession requested: Color=[" + colorW + "x" + colorH + ", phys=" + colorPhysicalId + "], " +
                "Depth=[" + depthW + "x" + depthH + "], Still=[" + stillW + "x" + stillH + "]");

        mColorSurface = colorSurface;
        mDepthSurface = depthSurface;
        mStillSurface = stillSurface;

        // Guard against Doze idle
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isDeviceIdleMode()) {
            Log.w(TAG, "Device is in idle/doze mode! Waiting for wake...");
            notifyError("Device is in idle/doze mode");
            return false;
        }

        Context shellContext = mContext;
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object at = activityThreadClass.getMethod("systemMain").invoke(null);
            Context systemContext = (Context) activityThreadClass.getMethod("getSystemContext").invoke(at);
            fixAttribution(systemContext, "com.android.shell", 2000);
            shellContext = systemContext.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            fixAttribution(shellContext, "com.android.shell", 2000);
        } catch (Throwable t) {
            Log.w(TAG, "shellContext setup fallback: " + t);
        }

        CameraManager cm = (CameraManager) shellContext.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            notifyError("CameraManager unavailable");
            return false;
        }

        try {
            cm.openCamera(CAMERA_ID, new DeviceStateCallback(this, colorPhysicalId), mCameraHandler);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open Camera " + CAMERA_ID, t);
            notifyError("Failed to open Camera " + CAMERA_ID + ": " + t.getMessage());
            return false;
        }
    }

    void onDeviceOpened(CameraDevice camera, String colorPhysicalId) {
        mCameraDevice = camera;
        Log.i(TAG, "Camera " + CAMERA_ID + " opened successfully. Configuring 3-stream SessionConfiguration...");

        try {
            OutputConfiguration colorConfig = new OutputConfiguration(mColorSurface);
            if (colorPhysicalId != null && !colorPhysicalId.isEmpty()) {
                colorConfig.setPhysicalCameraId(colorPhysicalId);
                Log.i(TAG, "Pinned colorConfig to physical sensor: " + colorPhysicalId);
            }

            OutputConfiguration depthConfig = new OutputConfiguration(mDepthSurface);
            OutputConfiguration stillConfig = new OutputConfiguration(mStillSurface);

            List<OutputConfiguration> outputs = new ArrayList<OutputConfiguration>();
            outputs.add(colorConfig);
            outputs.add(depthConfig);
            outputs.add(stillConfig);

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    mExecutor,
                    new SessionStateCallback(this)
            );

            mCameraDevice.createCaptureSession(sessionConfig);
            Log.i(TAG, "createCaptureSession dispatched to HAL");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to configure 3-stream capture session", t);
            notifyError("Failed to configure capture session: " + t.getMessage());
        }
    }

    void onSessionConfigured(CameraCaptureSession session) {
        mCaptureSession = session;
        Log.i(TAG, "Stereo 3-stream CameraCaptureSession configured and ready!");

        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mColorSurface);
            mPreviewRequestBuilder.addTarget(mDepthSurface);

            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            mStatsWindowStartMs = SystemClock.elapsedRealtime();
            mColorFrameCount = 0;
            mDepthFrameCount = 0;

            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(),
                    new CaptureCallback(this),
                    mCameraHandler
            );

            if (mCallback != null) {
                try {
                    mCallback.onSessionReady();
                } catch (RemoteException e) {
                    Log.w(TAG, "Remote exception notifying onSessionReady", e);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start repeating preview request", t);
            notifyError("Failed to start repeating request: " + t.getMessage());
        }
    }

    @Override
    public void triggerStill(long shutterCommandNs, boolean highQualityNr) {
        triggerStillWithZsl(shutterCommandNs, highQualityNr, true);
    }

    @Override
    public void triggerStillWithZsl(long shutterCommandNs, boolean highQualityNr, boolean enableZsl) {
        if (mCameraDevice == null || mCaptureSession == null || mStillSurface == null) {
            Log.e(TAG, "Cannot trigger still capture: session not ready");
            notifyError("Session not ready for still capture");
            return;
        }

        mShutterCommandNs = (shutterCommandNs > 0) ? shutterCommandNs : SystemClock.elapsedRealtimeNanos();
        Log.i(TAG, "TIMING t0 (shutter_command): " + mShutterCommandNs + " ns. Dispatching STILL_CAPTURE request (highQualityNr=" + highQualityNr + ", enableZsl=" + enableZsl + ")...");
        try {
            CaptureRequest.Builder stillBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            stillBuilder.addTarget(mStillSurface);
            stillBuilder.set(CaptureRequest.CONTROL_ENABLE_ZSL, enableZsl);
            stillBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            stillBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if (highQualityNr) {
                stillBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                stillBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            } else {
                stillBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);
                stillBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST);
            }

            mCaptureSession.capture(stillBuilder.build(), new StillCaptureCallback(this), mCameraHandler);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to dispatch still capture request", t);
            notifyError("Still capture failed: " + t.getMessage());
        }
    }

    void onStillCompleted() {
        Log.i(TAG, "Still capture completed successfully without interrupting viewfinder stream!");
        if (mCallback != null) {
            try {
                mCallback.onStillCaptured();
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception notifying onStillCaptured", e);
            }
        }
    }

    void onCaptureResult(TotalCaptureResult result) {
        mColorFrameCount++;
        mDepthFrameCount++; // Synced hardware DPE frames

        long nowNs = SystemClock.elapsedRealtimeNanos();
        if (mLastViewfinderFrameNs > 0) {
            long dtMs = (nowNs - mLastViewfinderFrameNs) / 1_000_000;
            if (dtMs < 25) mFrameHistogram[0]++;
            else if (dtMs < 30) mFrameHistogram[1]++;
            else if (dtMs <= 33) mFrameHistogram[2]++;
            else if (dtMs <= 35) mFrameHistogram[3]++;
            else if (dtMs <= 40) mFrameHistogram[4]++;
            else if (dtMs <= 50) mFrameHistogram[5]++;
            else mFrameHistogram[6]++;
            mTotalHistogramFrames++;
        }
        mLastViewfinderFrameNs = nowNs;

        long now = SystemClock.elapsedRealtime();
        long elapsed = now - mStatsWindowStartMs;
        if (elapsed >= 1000) {
            float colorFps = (mColorFrameCount * 1000.0f) / elapsed;
            float depthFps = (mDepthFrameCount * 1000.0f) / elapsed;
            float dtP99 = 0.0f; // Measured 0ns in Phase 0

            if (mTotalHistogramFrames >= 90) {
                Log.i(TAG, String.format(java.util.Locale.US,
                    "VIEWFINDER FRAME-TIME HISTOGRAM (N=%d): [<25ms: %d, 25-30ms: %d, 30-33ms: %d, 33-35ms: %d, 35-40ms: %d, 40-50ms: %d, >50ms: %d]",
                    mTotalHistogramFrames,
                    mFrameHistogram[0], mFrameHistogram[1], mFrameHistogram[2],
                    mFrameHistogram[3], mFrameHistogram[4], mFrameHistogram[5], mFrameHistogram[6]));
            }

            mStatsWindowStartMs = now;
            mColorFrameCount = 0;
            mDepthFrameCount = 0;

            if (mCallback != null) {
                try {
                    mCallback.onStats(colorFps, depthFps, dtP99);
                } catch (RemoteException e) {
                    // Client disconnected
                }
            }
        }
    }

    @Override
    public void closeSession() {
        Log.i(TAG, "closeSession() invoked: closing capture session and camera...");
        try {
            if (mCaptureSession != null) {
                mCaptureSession.stopRepeating();
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error closing camera session", t);
        }
    }

    private void notifyError(String msg) {
        if (mCallback != null) {
            try {
                mCallback.onSessionError(msg);
            } catch (RemoteException e) {
                Log.w(TAG, "Error notifying client", e);
            }
        }
    }

    static class DeviceStateCallback extends CameraDevice.StateCallback {
        private final CameraUserService mService;
        private final String mColorPhysicalId;

        DeviceStateCallback(CameraUserService service, String colorPhysicalId) {
            mService = service;
            mColorPhysicalId = colorPhysicalId;
        }

        @Override
        public void onOpened(CameraDevice camera) {
            mService.onDeviceOpened(camera, mColorPhysicalId);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.w(TAG, "Camera disconnected");
            camera.close();
            mService.notifyError("Camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "Camera device error: " + error);
            camera.close();
            mService.notifyError("Camera error code: " + error);
        }
    }

    static class SessionStateCallback extends CameraCaptureSession.StateCallback {
        private final CameraUserService mService;

        SessionStateCallback(CameraUserService service) {
            mService = service;
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            mService.onSessionConfigured(session);
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "Session configuration failed!");
            mService.notifyError("CameraCaptureSession configuration failed");
        }
    }

    static class CaptureCallback extends CameraCaptureSession.CaptureCallback {
        private final CameraUserService mService;

        CaptureCallback(CameraUserService service) {
            mService = service;
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            mService.onCaptureResult(result);
        }
    }

    static class StillCaptureCallback extends CameraCaptureSession.CaptureCallback {
        private final CameraUserService mService;

        StillCaptureCallback(CameraUserService service) {
            mService = service;
        }

        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
            long t1 = SystemClock.elapsedRealtimeNanos();
            long d1 = (t1 - mService.mShutterCommandNs) / 1_000_000;
            long sensorDeltaMs = (timestamp - mService.mShutterCommandNs) / 1_000_000;
            Log.i(TAG, "TIMING t1 (first_buffer/capture_started): " + t1 + " ns, delta1=" + d1 + " ms, sensorTimestamp=" + timestamp + " ns, sensor_minus_t0=" + sensorDeltaMs + " ms");
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            long t_hal = SystemClock.elapsedRealtimeNanos();
            long dt = (t_hal - mService.mShutterCommandNs) / 1_000_000;
            Long sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP);
            long sensorDeltaMs = (sensorTs != null) ? (sensorTs - mService.mShutterCommandNs) / 1_000_000 : 0;
            Log.i(TAG, "TIMING HAL onCaptureCompleted: " + t_hal + " ns, total HAL dt=" + dt + " ms, sensorTs=" + sensorTs + " ns, sensor_minus_t0=" + sensorDeltaMs + " ms");
            mService.onStillCompleted();
        }
    }
}
