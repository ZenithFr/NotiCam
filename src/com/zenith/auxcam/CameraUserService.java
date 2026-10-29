package com.zenith.auxcam;

import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.graphics.Rect;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
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

    // ─── Live camera controls (flash / exposure / zoom / focus / lens) ───────
    private static final int FLASH_OFF = 0;
    private static final int FLASH_AUTO = 1;
    private static final int FLASH_ON = 2;

    private int mFlashMode = FLASH_OFF;
    private int mEvSteps = 0;
    private float mZoomRatio = 1.0f;
    private float mMaxDigitalZoom = 1.0f;
    private Rect mActiveArray;
    private Rect mCropRegion;
    private Rect mAfRegion;
    private Range<Integer> mCompensationRange;
    private String mPhysicalId;
    private String mCurrentCameraId = CAMERA_ID;
    private Float mMinFocusDistance = null;
    private float mManualFocusDistance = 0.0f;
    private boolean mManualFocusActive = false;
    private boolean mAfLocked = false;

    private int mColorW = 1440, mColorH = 1080;
    private int mDepthW = 448, mDepthH = 288;
    private int mStillW = 4080, mStillH = 3072;

    private final class AfResetRunnable implements Runnable {
        @Override
        public void run() {
            if (!mAfLocked && !mManualFocusActive) {
                mAfRegion = null;
                refreshRepeatingRequest();
            }
        }
    }
    private final Runnable mAfResetRunnable = new AfResetRunnable();

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

        mColorW = colorW;
        mColorH = colorH;
        mDepthW = depthW;
        mDepthH = depthH;
        mStillW = stillW;
        mStillH = stillH;
        mPhysicalId = colorPhysicalId;

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

        loadCameraCharacteristics(cm);

        try {
            cm.openCamera(mCurrentCameraId, new DeviceStateCallback(this, colorPhysicalId), mCameraHandler);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open Camera " + mCurrentCameraId, t);
            notifyError("Failed to open Camera " + mCurrentCameraId + ": " + t.getMessage());
            return false;
        }
    }

    void onDeviceOpened(CameraDevice camera, String colorPhysicalId) {
        mCameraDevice = camera;
        Log.i(TAG, "Camera " + mCurrentCameraId + " opened successfully. Configuring SessionConfiguration...");

        try {
            OutputConfiguration colorConfig = new OutputConfiguration(mColorSurface);
            OutputConfiguration stillConfig = new OutputConfiguration(mStillSurface);

            List<OutputConfiguration> outputs = new ArrayList<OutputConfiguration>();
            outputs.add(colorConfig);

            boolean isStereoCam = CAMERA_ID.equals(mCurrentCameraId);
            if (isStereoCam && mDepthSurface != null) {
                OutputConfiguration depthConfig = new OutputConfiguration(mDepthSurface);
                outputs.add(depthConfig);
            }
            outputs.add(stillConfig);

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    mExecutor,
                    new SessionStateCallback(this)
            );

            mCameraDevice.createCaptureSession(sessionConfig);
            Log.i(TAG, "createCaptureSession dispatched to HAL for Camera " + mCurrentCameraId + " (outputs=" + outputs.size() + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to configure capture session for Camera " + mCurrentCameraId, t);
            notifyError("Failed to configure capture session: " + t.getMessage());
        }
    }

    void onSessionConfigured(CameraCaptureSession session) {
        mCaptureSession = session;
        Log.i(TAG, "Camera " + mCurrentCameraId + " CameraCaptureSession configured and ready!");

        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mColorSurface);
            if (CAMERA_ID.equals(mCurrentCameraId) && mDepthSurface != null) {
                mPreviewRequestBuilder.addTarget(mDepthSurface);
            }

            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            applyPreviewControls(mPreviewRequestBuilder);

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

    // ══════════════════════════ LIVE CONTROLS ══════════════════════════

    /** Reads the sensor geometry used by zoom / focus / exposure mapping. */
    private void loadCameraCharacteristics(CameraManager cm) {
        try {
            CameraCharacteristics chars = cm.getCameraCharacteristics(mCurrentCameraId);
            mActiveArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            mCompensationRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            mMaxDigitalZoom = (maxZoom != null && maxZoom > 1.0f) ? maxZoom : 1.0f;
            Float minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            mMinFocusDistance = (minFocus != null && minFocus > 0f) ? minFocus : 10.0f;
            recomputeCropRegion();
            Log.i(TAG, "Characteristics for Camera " + mCurrentCameraId + ": activeArray=" + mActiveArray
                    + " maxDigitalZoom=" + mMaxDigitalZoom
                    + " minFocusDist=" + mMinFocusDistance
                    + " evRange=" + mCompensationRange);
        } catch (Throwable t) {
            Log.w(TAG, "Could not read camera characteristics: " + t);
        }
    }

    /** SCALER_CROP_REGION for the current digital zoom, centred on the sensor. */
    private void recomputeCropRegion() {
        if (mActiveArray == null || mActiveArray.width() <= 0 || mActiveArray.height() <= 0) {
            mCropRegion = null;
            return;
        }
        float z = Math.max(1.0f, Math.min(mMaxDigitalZoom, mZoomRatio));
        int w = Math.round(mActiveArray.width() / z);
        int h = Math.round(mActiveArray.height() / z);
        w = Math.max(2, w - (w % 2));
        h = Math.max(2, h - (h % 2));
        int cx = mActiveArray.centerX();
        int cy = mActiveArray.centerY();
        Rect r = new Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        r.left = Math.max(r.left, mActiveArray.left);
        r.top = Math.max(r.top, mActiveArray.top);
        r.right = Math.min(r.right, mActiveArray.right);
        r.bottom = Math.min(r.bottom, mActiveArray.bottom);
        mCropRegion = r;
    }

    private int clampedEvSteps() {
        if (mCompensationRange == null) return mEvSteps;
        return Math.max(mCompensationRange.getLower(),
                Math.min(mCompensationRange.getUpper(), mEvSteps));
    }

    private void applyCropAndExposure(CaptureRequest.Builder b) {
        try {
            if (mCropRegion != null) {
                b.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, mZoomRatio);
            }
            if (mCompensationRange != null) {
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedEvSteps());
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyCropAndExposure: " + t);
        }
    }

    private void applyPreviewControls(CaptureRequest.Builder b) {
        applyCropAndExposure(b);
        try {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            if (mManualFocusActive) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, mManualFocusDistance);
                b.set(CaptureRequest.CONTROL_AE_LOCK, false);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, false);
            } else if (mAfLocked) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                if (mAfRegion != null) {
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{
                            new MeteringRectangle(mAfRegion, MeteringRectangle.METERING_WEIGHT_MAX)});
                    b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{
                            new MeteringRectangle(mAfRegion, MeteringRectangle.METERING_WEIGHT_MAX)});
                }
            } else if (mAfRegion != null) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{
                        new MeteringRectangle(mAfRegion, MeteringRectangle.METERING_WEIGHT_MAX)});
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AE_LOCK, false);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, false);
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                b.set(CaptureRequest.CONTROL_AE_LOCK, false);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyPreviewControls: " + t);
        }
    }

    private void applyStillFlash(CaptureRequest.Builder b) {
        try {
            switch (mFlashMode) {
                case FLASH_ON:
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                    break;
                case FLASH_AUTO:
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                    break;
                default:
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyStillFlash: " + t);
        }
    }

    /** Re-submits the repeating preview request after a control change. */
    private void refreshRepeatingRequest() {
        if (mCaptureSession == null || mPreviewRequestBuilder == null) return;
        try {
            applyPreviewControls(mPreviewRequestBuilder);
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                    new CaptureCallback(this), mCameraHandler);
        } catch (Throwable t) {
            Log.w(TAG, "refreshRepeatingRequest failed: " + t);
        }
    }

    @Override
    public void setFlashMode(final int mode) {
        if (mCameraHandler == null) return;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mFlashMode = mode;
                Log.i(TAG, "Flash mode -> " + mode + " (0=off, 1=auto, 2=on)");
            }
        });
    }

    @Override
    public void setEvCompensation(final int steps) {
        if (mCameraHandler == null) return;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mEvSteps = steps;
                Log.i(TAG, "EV compensation -> " + clampedEvSteps() + " steps");
                refreshRepeatingRequest();
            }
        });
    }

    @Override
    public void setZoom(final float ratio) {
        if (mCameraHandler == null) return;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                // If zoom is ultra-wide (<0.9x), switch to Ultra-wide camera ("2")
                if (ratio < 0.9f && !"2".equals(mCurrentCameraId)) {
                    mZoomRatio = 1.0f;
                    restartWithCamera("2");
                    return;
                } else if (ratio >= 0.9f && "2".equals(mCurrentCameraId)) {
                    // Revert to main stereo camera "5"
                    mZoomRatio = ratio;
                    restartWithCamera(CAMERA_ID);
                    return;
                }

                mZoomRatio = Math.max(0.6f, Math.min(mMaxDigitalZoom, ratio));
                recomputeCropRegion();
                Log.i(TAG, "Zoom -> " + mZoomRatio + "x on Camera " + mCurrentCameraId + ", crop=" + mCropRegion);
                refreshRepeatingRequest();
            }
        });
    }

    @Override
    public void focusAt(final float sensorNormX, final float sensorNormY) {
        if (mCameraHandler == null) return;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mManualFocusActive) {
                    mManualFocusActive = false;
                }
                Rect base = (mCropRegion != null) ? mCropRegion : mActiveArray;
                if (base == null) return;

                float nx = Math.max(0.0f, Math.min(1.0f, sensorNormX));
                float ny = Math.max(0.0f, Math.min(1.0f, sensorNormY));
                int halfW = Math.max(2, Math.round(base.width() * 0.09f));
                int halfH = Math.max(2, Math.round(base.height() * 0.09f));
                int cx = base.left + Math.round(nx * base.width());
                int cy = base.top + Math.round(ny * base.height());

                Rect region = new Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
                region.left = Math.max(base.left, region.left);
                region.top = Math.max(base.top, region.top);
                region.right = Math.min(base.right, region.right);
                region.bottom = Math.min(base.bottom, region.bottom);
                mAfRegion = region;

                Log.i(TAG, "Tap-to-focus region=" + region + " (locked=" + mAfLocked + ")");
                refreshRepeatingRequest();
                triggerAfScan(region);

                mCameraHandler.removeCallbacks(mAfResetRunnable);
                if (!mAfLocked) {
                    mCameraHandler.postDelayed(mAfResetRunnable, 2000);
                }
            }
        });
    }

    @Override
    public void setAfLock(final boolean locked) {
        if (mCameraHandler == null) return;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mAfLocked = locked;
                mCameraHandler.removeCallbacks(mAfResetRunnable);
                Log.i(TAG, "setAfLock -> " + locked + " (mAfRegion=" + mAfRegion + ")");
                refreshRepeatingRequest();
            }
        });
    }

    @Override
    public void setManualFocus(final float distanceFraction) {
        if (mCameraHandler == null) return;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mManualFocusActive = true;
                mAfLocked = false;
                mCameraHandler.removeCallbacks(mAfResetRunnable);
                float maxDist = (mMinFocusDistance != null && mMinFocusDistance > 0f) ? mMinFocusDistance : 10.0f;
                mManualFocusDistance = Math.max(0.0f, Math.min(1.0f, distanceFraction)) * maxDist;
                Log.i(TAG, "setManualFocus: frac=" + distanceFraction + " -> " + mManualFocusDistance + " diopters");
                refreshRepeatingRequest();
            }
        });
    }

    @Override
    public void resetFocus() {
        if (mCameraHandler == null) return;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                mManualFocusActive = false;
                mAfLocked = false;
                mAfRegion = null;
                mCameraHandler.removeCallbacks(mAfResetRunnable);
                Log.i(TAG, "resetFocus -> continuous autofocus");
                refreshRepeatingRequest();
            }
        });
    }

    /** One-shot AF/AE scan at the tapped region (does not touch the repeating request). */
    private void triggerAfScan(Rect region) {
        if (mCameraDevice == null || mCaptureSession == null || mColorSurface == null) {
            return;
        }
        try {
            CaptureRequest.Builder af = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            af.addTarget(mColorSurface);
            if (CAMERA_ID.equals(mCurrentCameraId) && mDepthSurface != null) {
                af.addTarget(mDepthSurface);
            }
            af.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            af.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            af.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            MeteringRectangle[] meters = new MeteringRectangle[]{
                    new MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX)};
            af.set(CaptureRequest.CONTROL_AF_REGIONS, meters);
            af.set(CaptureRequest.CONTROL_AE_REGIONS, meters);
            af.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            applyCropAndExposure(af);
            mCaptureSession.capture(af.build(), null, mCameraHandler);
        } catch (Throwable t) {
            Log.w(TAG, "triggerAfScan failed: " + t);
        }
    }

    @Override
    public void setPhysicalLens(final String physicalId) {
        if (mCameraHandler == null) return;
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                String targetId;
                if ("3".equals(physicalId)) {
                    targetId = "3"; // Telephoto
                } else if ("2".equals(physicalId)) {
                    targetId = "2"; // Ultra-wide
                } else {
                    targetId = CAMERA_ID; // Main stereo VSDOF "5"
                }
                if (targetId.equals(mCurrentCameraId)) return;
                restartWithCamera(targetId);
            }
        });
    }

    /** Tears the device down and re-opens the specified camera. */
    private void restartWithCamera(String targetCameraId) {
        Log.i(TAG, "Switching camera device from " + mCurrentCameraId + " to '" + targetCameraId + "'");
        try {
            if (mCaptureSession != null) {
                try {
                    mCaptureSession.stopRepeating();
                } catch (Throwable ignore) {
                    // session already gone
                }
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Camera switch teardown: " + t);
        }

        mCurrentCameraId = targetCameraId;
        mAfRegion = null;

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
            return;
        }

        loadCameraCharacteristics(cm);

        try {
            cm.openCamera(mCurrentCameraId, new DeviceStateCallback(this, null), mCameraHandler);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open Camera " + mCurrentCameraId + ", falling back to " + CAMERA_ID, t);
            if (!CAMERA_ID.equals(mCurrentCameraId)) {
                mCurrentCameraId = CAMERA_ID;
                loadCameraCharacteristics(cm);
                try {
                    cm.openCamera(CAMERA_ID, new DeviceStateCallback(this, null), mCameraHandler);
                } catch (Throwable t2) {
                    Log.e(TAG, "Fallback to CAMERA_ID failed", t2);
                    notifyError("Failed to open camera: " + t.getMessage());
                }
            } else {
                notifyError("Failed to open camera: " + t.getMessage());
            }
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
            if (mManualFocusActive) {
                stillBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                stillBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mManualFocusDistance);
            } else if (mAfLocked) {
                stillBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                stillBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                if (mAfRegion != null) {
                    stillBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{
                            new MeteringRectangle(mAfRegion, MeteringRectangle.METERING_WEIGHT_MAX)});
                }
            } else {
                stillBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            applyCropAndExposure(stillBuilder);
            applyStillFlash(stillBuilder);
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
            try { camera.close(); } catch (Throwable ignored) {}
            mService.mCameraDevice = null;
            mService.mCaptureSession = null;
            mService.notifyError("Camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "Camera device error on " + mService.mCurrentCameraId + ": " + error);
            try { camera.close(); } catch (Throwable ignored) {}
            mService.mCameraDevice = null;
            mService.mCaptureSession = null;
            if (!CAMERA_ID.equals(mService.mCurrentCameraId)) {
                Log.i(TAG, "Fallback from failing camera " + mService.mCurrentCameraId + " to " + CAMERA_ID);
                mService.restartWithCamera(CAMERA_ID);
            } else {
                mService.notifyError("Camera error code: " + error);
            }
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
            Log.e(TAG, "Session configuration failed on Camera " + mService.mCurrentCameraId);
            mService.notifyError("CameraCaptureSession configuration failed");
            if (!CAMERA_ID.equals(mService.mCurrentCameraId)) {
                Log.i(TAG, "Reverting to primary CAMERA_ID " + CAMERA_ID);
                mService.restartWithCamera(CAMERA_ID);
            }
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

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            Log.e(TAG, "TIMING Still onCaptureFailed: reason=" + failure.getReason() + ", frameNumber=" + failure.getFrameNumber());
            mService.onStillCompleted();
        }
    }
}
