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
import android.graphics.ImageFormat;
import android.util.Size;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.DngCreator;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
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
    private Context mCameraContext;
    private CameraManager mPrivilegedCameraManager;
    private String mCameraContextSource = "not initialized";
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;
    private HandlerExecutor mExecutor;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private volatile IAuxCamCallback mCallback;
    private android.os.IBinder mClientBinder;
    private final Object mCallbackLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean mDestroying = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final android.os.IBinder.DeathRecipient mClientDeath = this::destroy;

    private Surface mColorSurface;
    private Surface mDepthSurface;
    private Surface mStillSurface;
    private Surface mRecordingSurface;
    private ImageReader mRawImageReader;
    private RawJob mRawJob; // Camera handler only. Writers own only their matched Image.
    private final java.util.concurrent.atomic.AtomicInteger mRawWritesPending = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ExecutorService mRawWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "RawDngWriter"); thread.setDaemon(true); return thread;
    });
    private static final class RawJob {
        final int token, generation, orientation;
        final IAuxCamCallback owner;
        final CameraCharacteristics characteristics;
        final ParcelFileDescriptor[] outputs;
        final RawFrameMatcher<Image, TotalCaptureResult> matcher;
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        int saved;
        Runnable timeout;
        RawJob(int token, int generation, int orientation, IAuxCamCallback owner,
                CameraCharacteristics characteristics, ParcelFileDescriptor[] outputs) {
            this.token = token; this.generation = generation; this.orientation = orientation;
            this.owner = owner; this.characteristics = characteristics; this.outputs = outputs;
            matcher = new RawFrameMatcher<>(outputs.length);
        }
    }
    private boolean mVideoMode;
    private boolean mHighResolutionStill;
    private volatile CameraCapabilities.Target mHighResolutionTarget;
    private volatile int mRemosaicStatus = 0;

    private CaptureRequest.Builder mPreviewRequestBuilder;
    long mShutterCommandNs = 0;
    public static volatile boolean sForceFps30 = false;
    public static volatile float sLatestS1Matte = 0.65f;
    private long mLastCadenceLogMs = 0;

    // ─── Live camera controls (flash / exposure / zoom / focus / lens) ───────
    private static final int FLASH_OFF = 0;
    private static final int FLASH_AUTO = 1;
    private static final int FLASH_ON = 2;

    private int mFlashMode = FLASH_OFF;
    private int mEvSteps = 0;
    private float mZoomRatio = 1.0f;
    private float mRequestedZoom = 1.0f;
    private int mPreviewMaxFps = 30;
    private int mVideoRecordFps = 30;
    private Range<Integer> mPreviewFpsRange;
    private float mMaxDigitalZoom = 1.0f;
    private Rect mActiveArray;
    private Rect mMaximumActiveArray;
    private Rect mCropRegion;
    private Rect mAfRegion;
    private Range<Integer> mCompensationRange;
    private String mPhysicalId;
    private String mCurrentCameraId = CAMERA_ID;
    private volatile int mSessionGeneration = 0;
    private int[] mNoiseModes;
    private int[] mEdgeModes;
    private int[] mRotateAndCropModes;
    private Float mMinFocusDistance = null;
    private float mManualFocusDistance = 0.0f;
    private boolean mManualFocusActive = false;
    private boolean mAfLocked = false;
    private volatile SensorControlCapabilities mSensorControls = new SensorControlCapabilities();
    private boolean mManualExposureEnabled = false;
    private int mManualIso = 100;
    private long mManualExposureNs = 8_000_000L;
    private int mAwbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    private final java.util.concurrent.atomic.AtomicInteger mLatestSensorControlToken = new java.util.concurrent.atomic.AtomicInteger();


    private int mColorW = 1440, mColorH = 1080;
    private int mDepthW = 448, mDepthH = 288;
    private int mStillW = 4080, mStillH = 3072;
    private int mStillFormat = ImageFormat.YUV_420_888;
    private int mSensorOrientation = 90;
    private int mStillPixelMode = CaptureRequest.SENSOR_PIXEL_MODE_DEFAULT;

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

    /** One privileged client for discovery AND capture. Attribution is not a UID escalation. */
    private synchronized CameraManager cameraManager() {
        if (mPrivilegedCameraManager != null) return mPrivilegedCameraManager;
        Context shellContext = mContext;
        mCameraContextSource = "UserService context (shell context setup unavailable)";
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object at = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            if (at == null) at = activityThreadClass.getMethod("systemMain").invoke(null);
            Context systemContext = (Context) activityThreadClass.getMethod("getSystemContext").invoke(at);
            fixAttribution(systemContext, "com.android.shell", 2000);
            shellContext = systemContext.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            fixAttribution(shellContext, "com.android.shell", 2000);
            mCameraContextSource = "com.android.shell context";
        } catch (Throwable t) {
            Log.w(TAG, "shellContext setup fallback: " + t);
        }
        mCameraContext = shellContext;
        mPrivilegedCameraManager = (CameraManager) shellContext.getSystemService(Context.CAMERA_SERVICE);
        return mPrivilegedCameraManager;
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
        synchronized (mCallbackLock) {
            if (mDestroying.get()) return;
            if (mClientBinder != null) {
                try { mClientBinder.unlinkToDeath(mClientDeath, 0); } catch (Exception ignored) { }
            }
            mCallback = cb;
            mClientBinder = cb == null ? null : cb.asBinder();
            if (mClientBinder != null) try {
                mClientBinder.linkToDeath(mClientDeath, 0);
            } catch (RemoteException deadClient) { destroy(); }
        }
    }

    /** Shizuku calls this reserved transaction when the final client removes
     * the service. Client-process death also calls it, including crashes/swipes.
     * Never kill cameraserver, Shizuku itself, or another app's camera process.
     */
    @Override
    public void destroy() {
        if (!mDestroying.compareAndSet(false, true)) return;
        // A HAL call can stall the camera handler. A bounded watchdog still
        // terminates THIS dedicated user-service process and its Binder owners.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "CameraExitWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        synchronized (mCallbackLock) {
            if (mClientBinder != null) {
                try { mClientBinder.unlinkToDeath(mClientDeath, 0); } catch (Exception ignored) { }
            }
            mClientBinder = null;
            mCallback = null;
        }
        Handler handler = mCameraHandler;
        if (handler == null || !handler.postAtFrontOfQueue(() -> {
            try { closeSessionOnCameraThread(); }
            finally {
                if (mCameraThread != null) mCameraThread.quitSafely();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        })) android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public boolean openStereoSession(
            Surface colorSurface, int colorW, int colorH,
            Surface depthSurface, int depthW, int depthH,
            Surface stillSurface, int stillW, int stillH, int stillFormat,
            String colorPhysicalId, Surface recordingSurface, boolean videoMode, float requestedZoom, int previewMaxFps) {
        if (mCameraHandler == null || mDestroying.get()) return false;
        mCameraHandler.post(() -> openSessionOnCameraThread(colorSurface, colorW, colorH,
                depthSurface, depthW, depthH, stillSurface, stillW, stillH, stillFormat, colorPhysicalId, recordingSurface, videoMode, requestedZoom, previewMaxFps));
        return true; // accepted; readiness/failure is delivered through the callback
    }

    private void openSessionOnCameraThread(
            Surface colorSurface, int colorW, int colorH,
            Surface depthSurface, int depthW, int depthH,
            Surface stillSurface, int stillW, int stillH, int stillFormat, String colorPhysicalId, Surface recordingSurface, boolean videoMode, float requestedZoom, int previewMaxFps) {
        if (mDestroying.get()) return;
        closeSessionOnCameraThread();
        Log.i(TAG, "openStereoSession requested: Color=[" + colorW + "x" + colorH + ", phys=" + colorPhysicalId + "], " +
                "Depth=[" + depthW + "x" + depthH + "], Still=[" + stillW + "x" + stillH + "]");

        mColorSurface = colorSurface;
        mDepthSurface = depthSurface;
        mStillSurface = stillSurface;
        mRecordingSurface = recordingSurface;
        CameraManager cm = cameraManager();
        if (cm == null) {
            closeSessionOnCameraThread();
            notifyError("CameraManager unavailable"); return;
        }
        if (stillFormat == ImageFormat.RAW_SENSOR) {
            Size rawSize = CameraCapabilities.rawSize(cm, colorPhysicalId);
            if (rawSize == null || rawSize.getWidth() != stillW || rawSize.getHeight() != stillH ||
                    RawFrameMatcher.maxFramesForSize(stillW, stillH) == 0) {
                closeSessionOnCameraThread();
                notifyError("RAW size not advertised or exceeds capture payload budget"); return;
            }
            try {
                // Validate before allocating a reader or opening the camera.
                mRawImageReader = ImageReader.newInstance(stillW, stillH, ImageFormat.RAW_SENSOR, RawFrameMatcher.MAX_FRAMES);
                mStillSurface = mRawImageReader.getSurface();
                if (stillSurface != null) stillSurface.release(); // Unused parcelled client reference.
                mRawImageReader.setOnImageAvailableListener(this::onRawImageAvailable, mCameraHandler);
            } catch (RuntimeException failure) {
                closeSessionOnCameraThread();
                notifyError("RAW reader allocation failed"); return;
            }
        }
        mVideoMode = videoMode;
        mZoomRatio = Float.isFinite(requestedZoom) ? Math.max(.6f, Math.min(10f, requestedZoom)) : 1f;
        mRequestedZoom = mZoomRatio;
        mPreviewMaxFps = previewMaxFps >= 60 && recordingSurface == null ? 60 : 30;
        mVideoRecordFps = previewMaxFps >= 60 ? 60 : 30;
        mHighResolutionStill = !videoMode && stillFormat == ImageFormat.JPEG && (long) stillW * stillH >= 45_000_000L;

        mColorW = colorW;
        mColorH = colorH;
        mDepthW = depthW;
        mDepthH = depthH;
        mStillW = stillW;
        mStillH = stillH;
        mStillFormat = stillFormat;
        mPhysicalId = colorPhysicalId;
        mAfRegion = null;
        mAfLocked = false;
        mManualFocusActive = false;
        mCurrentCameraId = "2".equals(colorPhysicalId) || "3".equals(colorPhysicalId) ? colorPhysicalId :
                (videoMode || stillFormat == ImageFormat.JPEG || stillFormat == ImageFormat.RAW_SENSOR ? "0" : CAMERA_ID);

        if (mHighResolutionStill) {
            int[] size = getHighResolutionJpegSize(colorPhysicalId);
            if (size.length != 3 || Math.abs(size[0] - mStillW) > 32 || size[1] != mStillH) {
                notifyError("Requested 50 MP JPEG size is not advertised by camera " + colorPhysicalId);
                return;
            }
            mStillW = size[0];
            mStillH = size[1];
            mStillPixelMode = size[2];
            mCurrentCameraId = mHighResolutionTarget.cameraId;
            Log.i(TAG, "50 MP path: " + mCurrentCameraId + " / " + mHighResolutionTarget.source);
        } else {
            mStillPixelMode = CaptureRequest.SENSOR_PIXEL_MODE_DEFAULT;
        }
        mPreviewFpsRange = null;
        loadCameraCharacteristics(cm);
        if (mPreviewFpsRange == null) { notifyError("No advertised frame rate for selected preview stream"); return; }

        try {
            cm.openCamera(mCurrentCameraId, new DeviceStateCallback(this, colorPhysicalId), mCameraHandler);
            return;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open Camera " + mCurrentCameraId, t);
            notifyError("Failed to open Camera " + mCurrentCameraId + ": " + t.getMessage());
            return;
        }
    }

    @Override
    public int[] getHighResolutionJpegSize() {
        try {
            CameraManager cm = cameraManager();
            String id = "3".equals(mCurrentCameraId) ? "3" : "0";
            CameraCapabilities.Target target = CameraCapabilities.discover(cm, id);
            mHighResolutionTarget = target;
            if (target != null) return new int[]{target.size.getWidth(), target.size.getHeight(), target.pixelMode};
        } catch (Throwable t) {
            Log.e(TAG, "Failed discovering high-resolution JPEG size", t);
        }
        return new int[0];
    }

    public int[] getHighResolutionJpegSize(String lensId) {
        try {
            CameraManager cm = cameraManager();
            CameraCapabilities.Target target = CameraCapabilities.discover(cm, lensId);
            mHighResolutionTarget = target;
            if (target != null) return new int[]{target.size.getWidth(), target.size.getHeight(), target.pixelMode};
        } catch (Throwable t) { Log.w(TAG, "50 MP capability query failed", t); }
        return new int[0];
    }

    @Override
    public int[] getRawSensorSize(String lensId) {
        try {
            CameraManager cm = cameraManager();
            Size size = CameraCapabilities.rawSize(cm, lensId);
            if (size != null) return new int[]{size.getWidth(), size.getHeight()};
        } catch (Throwable t) { Log.w(TAG, "RAW size query failed", t); }
        return new int[0];
    }

    @Override
    public int[] getStandardJpegSize(String lensId) {
        try {
            CameraManager cm = cameraManager();
            String id = "2".equals(lensId) || "3".equals(lensId) ? lensId : "0";
            Size size = CameraCapabilities.jpegSize(cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), false);
            if (size != null) return new int[]{size.getWidth(), size.getHeight()};
        } catch (Throwable t) { Log.w(TAG, "JPEG size query failed", t); }
        return new int[0];
    }

    @Override
    public int[] getVideoSize(String lensId) {
        try {
            CameraManager cm = cameraManager();
            String id = "2".equals(lensId) || "3".equals(lensId) ? lensId : "0";
            Size size = CameraCapabilities.fourKVideoSize(cm, id);
            if (size == null) size = CameraCapabilities.videoSize(cm, id);
            if (size != null) {
                Integer orientation = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION);
                return new int[]{size.getWidth(), size.getHeight(), orientation == null ? 90 : orientation};
            }
        } catch (Throwable t) { Log.w(TAG, "Video size query failed", t); }
        return new int[0];
    }

    @Override
    public int[] getPreviewConfiguration(String lensId, boolean bokeh, boolean highResolution, boolean prefer60) {
        try {
            CameraManager cm = cameraManager();
            String id = bokeh ? CAMERA_ID : ("2".equals(lensId) || "3".equals(lensId) ? lensId : "0");
            if (highResolution) {
                CameraCapabilities.Target target = CameraCapabilities.discover(cm);
                if (target != null) id = target.cameraId;
            }
            boolean diagnostic30 = sForceFps30 || new java.io.File("/data/local/tmp/force_fps_30").exists();
            return CameraCapabilities.previewConfig(cm, id, prefer60 && !diagnostic30);
        } catch (Throwable t) { Log.w(TAG, "Preview capability query failed", t); }
        return new int[0];
    }

    @Override
    public String getCameraDiagnostics() {
        try {
            CameraManager cm = cameraManager();
            String attribution = "not available before API 31";
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.content.AttributionSource source = mCameraContext.getAttributionSource();
                attribution = source.getPackageName() + " / uid=" + source.getUid();
            }
            String header = "Backend: Shizuku UserService\nProcess UID: " + android.os.Process.myUid()
                    + " (shell=2000; root=0; attribution does not change this)\nContext: " + mCameraContextSource
                    + "\nCamera attribution: " + attribution
                    + "\nCAMERA permission: " + (mCameraContext.checkSelfPermission("android.permission.CAMERA") == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    + "\nSYSTEM_CAMERA permission: " + (mCameraContext.checkSelfPermission("android.permission.SYSTEM_CAMERA") == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    + "\nNo global permissions, app-ops, sensor privacy or vendor properties changed.\n\n";
            return header + CameraCapabilities.report(cm);
        } catch (Throwable t) { return "Camera report failed (UserService UID=" + android.os.Process.myUid() + "): " + t; }
    }

    void onDeviceOpened(CameraDevice camera, String colorPhysicalId) {
        mCameraDevice = camera;
        Log.i(TAG, "Camera " + mCurrentCameraId + " opened successfully. Configuring SessionConfiguration...");

        try {
            OutputConfiguration colorConfig = new OutputConfiguration(mColorSurface);
            List<OutputConfiguration> outputs = new ArrayList<OutputConfiguration>();
            outputs.add(colorConfig);
            if (mVideoMode) {
                if (mRecordingSurface != null) outputs.add(new OutputConfiguration(mRecordingSurface));
            } else {
                OutputConfiguration stillConfig = new OutputConfiguration(mStillSurface);
                if (mHighResolutionStill && android.os.Build.VERSION.SDK_INT >= 31 &&
                        (mHighResolutionTarget == null || !"Nothing camera 50 MP stream map".equals(mHighResolutionTarget.source))) {
                    stillConfig.addSensorPixelModeUsed(mStillPixelMode);
                }
                if (!mVideoMode && !mHighResolutionStill && CAMERA_ID.equals(mCurrentCameraId) && mDepthSurface != null) outputs.add(new OutputConfiguration(mDepthSurface));
                outputs.add(stillConfig);
            }

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
            closeSessionOnCameraThread();
            notifyError("Failed to configure capture session: " + t.getMessage());
        }
    }

    void onSessionConfigured(CameraCaptureSession session) {
        mCaptureSession = session;
        Log.i(TAG, "Camera " + mCurrentCameraId + " CameraCaptureSession configured and ready!");

        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(mVideoMode ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mColorSurface);
            if (mVideoMode && mRecordingSurface != null) mPreviewRequestBuilder.addTarget(mRecordingSurface);
            if (!mVideoMode && !mHighResolutionStill && CAMERA_ID.equals(mCurrentCameraId) && mDepthSurface != null) {
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

            IAuxCamCallback callback = mCallback;
            if (callback != null) {
                try {
                    callback.onSessionReady(mCurrentCameraId);
                } catch (RemoteException e) {
                    Log.w(TAG, "Remote exception notifying onSessionReady", e);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start repeating preview request", t);
            closeSessionOnCameraThread();
            notifyError("Failed to start repeating request: " + t.getMessage());
        }
    }

    // ══════════════════════════ LIVE CONTROLS ══════════════════════════

    /** Reads the sensor geometry used by zoom / focus / exposure mapping. */
    private void loadCameraCharacteristics(CameraManager cm) {
        try {
            CameraCharacteristics chars = cm.getCameraCharacteristics(mCurrentCameraId);
            mSensorControls = readSensorControlCapabilities(chars);
            if (!mSensorControls.getHasManualExposure() || mHighResolutionStill ||
                    SensorControlPolicy.maxExposure(mSensorControls, mVideoMode, mVideoRecordFps) == 0L)
                mManualExposureEnabled = false;
            mManualIso = SensorControlPolicy.iso(mManualIso, mSensorControls);
            mManualExposureNs = SensorControlPolicy.exposure(mManualExposureNs, mSensorControls, mVideoMode, mVideoRecordFps);
            if (!mSensorControls.supportsWhiteBalance(mAwbMode)) mAwbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
            mPreviewFpsRange = CameraCapabilities.previewRange(cm, mCurrentCameraId, new Size(mColorW, mColorH), mPreviewMaxFps);
            if (mPreviewFpsRange == null) throw new IllegalStateException("No advertised FPS range for the preview stream");
            Log.i(TAG, "PREVIEW_TARGET: " + mColorW + "x" + mColorH + " " + mPreviewFpsRange + " (still " + mStillW + "x" + mStillH + " unchanged)");
            Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mSensorOrientation = orientation == null ? 90 : orientation;
            mNoiseModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
            mEdgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
            mRotateAndCropModes = android.os.Build.VERSION.SDK_INT >= 31
                    ? chars.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES) : null;
            mActiveArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            mMaximumActiveArray = android.os.Build.VERSION.SDK_INT >= 31
                    ? chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION) : null;
            mCompensationRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            mMaxDigitalZoom = (maxZoom != null && maxZoom > 1.0f) ? maxZoom : 1.0f;
            Float minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            mMinFocusDistance = (minFocus != null && minFocus > 0f) ? minFocus : 10.0f;
            android.util.Range<Integer>[] fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fpsRanges != null) {
                StringBuilder sb = new StringBuilder("AE_FPS_RANGES: [");
                for (int i = 0; i < fpsRanges.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("[").append(fpsRanges[i].getLower()).append(",").append(fpsRanges[i].getUpper()).append("]");
                }
                sb.append("]");
                Log.i(TAG, sb.toString());
            }
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
        float z = CameraZoom.digitalRatio(mZoomRatio, mCurrentCameraId, mMaxDigitalZoom);
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
        applyCropAndExposure(b, false);
    }

    private void applyCropAndExposure(CaptureRequest.Builder b, boolean fullResolutionStill) {
        try {
            // JNI/MP4 orientation already follows sensor metadata. Don't let
            // portrait-app compatibility apply another rotation/aspect crop.
            if (android.os.Build.VERSION.SDK_INT >= 31 && mRotateAndCropModes != null) {
                for (int mode : mRotateAndCropModes) if (mode == CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) {
                    b.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
                    break;
                }
            }
            if (fullResolutionStill) {
                // Maximum-resolution mode has a different sensor coordinate
                // domain. Only set SCALER_CROP_REGION if mMaximumActiveArray is explicitly known.
                if (mMaximumActiveArray != null) {
                    b.set(CaptureRequest.SCALER_CROP_REGION, mMaximumActiveArray);
                }
            } else if (mCropRegion != null) {
                b.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
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
        applyMinimalProcessing(b);
        try {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            if (mVideoMode) {
                b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
                if (mVideoRecordFps >= 60 || mPreviewMaxFps >= 60) {
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 60));
                    try {
                        b.set(new CaptureRequest.Key<Integer>("com.mediatek.streamingfeature.hfpsMode", Integer.class), 1);
                    } catch (Throwable ignored) {}
                } else if (mPreviewFpsRange != null) {
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mPreviewFpsRange);
                }
            } else if (mPreviewFpsRange != null) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mPreviewFpsRange);
            }
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
                b.set(CaptureRequest.CONTROL_AF_MODE, mVideoMode ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                b.set(CaptureRequest.CONTROL_AE_LOCK, false);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyPreviewControls: " + t);
        }
        applySensorControls(b, false);
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

    private static boolean containsMode(int[] modes, int wanted) {
        if (modes != null) for (int mode : modes) if (mode == wanted) return true;
        return false;
    }

    private static SensorControlCapabilities readSensorControlCapabilities(CameraCharacteristics chars) {
        int[] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        int[] aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        Range<Integer> iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> exposure = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Long frame = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        java.util.List<CaptureRequest.Key<?>> keys = chars.getAvailableCaptureRequestKeys();
        boolean manual = containsMode(capabilities, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                && containsMode(aeModes, CaptureRequest.CONTROL_AE_MODE_OFF) && keys != null
                && keys.contains(CaptureRequest.SENSOR_EXPOSURE_TIME) && keys.contains(CaptureRequest.SENSOR_SENSITIVITY)
                && keys.contains(CaptureRequest.SENSOR_FRAME_DURATION);
        long awb = 0L;
        int[] awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (keys != null && keys.contains(CaptureRequest.CONTROL_AWB_MODE) && awbModes != null)
            for (int mode : awbModes) if (mode >= 1 && mode <= 8) awb |= 1L << mode;
        return new SensorControlCapabilities(manual, iso == null ? 0 : iso.getLower(), iso == null ? 0 : iso.getUpper(),
                exposure == null ? 0L : exposure.getLower(), exposure == null ? 0L : exposure.getUpper(),
                frame == null ? 0L : frame, awb);
    }

    @Override
    public long[] getSensorControlCapabilities() { return mSensorControls.toWire(); }

    private void applySensorControls(CaptureRequest.Builder b, boolean still) {
        SensorControlCapabilities caps = mSensorControls;
        if (caps.supportsWhiteBalance(mAwbMode)) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, mAwbMode);
            if (mAwbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO) b.set(CaptureRequest.CONTROL_AWB_LOCK, false);
        }
        if (!caps.getHasManualExposure()) return;
        if (mManualExposureEnabled && !mHighResolutionStill) {
            int fps = mVideoMode ? mVideoRecordFps : mPreviewMaxFps;
            long exposure = still ? SensorControlPolicy.exposure(mManualExposureNs, caps, false, fps)
                    : SensorControlPolicy.liveExposure(mManualExposureNs, caps, fps);
            if (exposure <= 0L) throw new IllegalStateException("No manual exposure fits the current stream");
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.CONTROL_AE_LOCK, false);
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, SensorControlPolicy.iso(mManualIso, caps));
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, SensorControlPolicy.frameDuration(exposure, caps, fps));
        } else {
            // The repeating builder is reused: remove manual keys when returning to Auto.
            b.set(CaptureRequest.SENSOR_SENSITIVITY, null);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, null);
        }
    }

    @Override
    public void setSensorControls(final int token, final boolean manual, final int iso, final long exposureNs, final int awbMode) {
        if (mCameraHandler == null) return;
        final int generation = mSessionGeneration;
        final IAuxCamCallback owner = mCallback;
        mLatestSensorControlToken.set(token);
        mCameraHandler.post(() -> {
            if (generation != mSessionGeneration || owner != mCallback) return;
            if (token != mLatestSensorControlToken.get()) return; // Coalesce queued slider updates.
            boolean oldManual = mManualExposureEnabled;
            int oldIso = mManualIso, oldAwb = mAwbMode;
            long oldExposure = mManualExposureNs;
            String error = null;
            try {
                SensorControlCapabilities caps = mSensorControls;
                if (mCaptureSession == null || mPreviewRequestBuilder == null) throw new IllegalStateException("Camera not ready");
                if (manual && (!caps.getHasManualExposure() || mHighResolutionStill || mFlashMode != FLASH_OFF))
                    throw new IllegalArgumentException("Manual exposure needs a supported standard-resolution lens with flash off");
                if (awbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO && !caps.supportsWhiteBalance(awbMode))
                    throw new IllegalArgumentException("White balance preset not advertised");
                long actualExposure = SensorControlPolicy.exposure(exposureNs, caps, mVideoMode, mVideoRecordFps);
                if (manual && (actualExposure == 0L || SensorControlPolicy.liveExposure(actualExposure, caps,
                        mVideoMode ? mVideoRecordFps : mPreviewMaxFps) == 0L))
                    throw new IllegalArgumentException("Exposure does not fit this stream");
                mManualExposureEnabled = manual;
                mManualIso = SensorControlPolicy.iso(iso, caps);
                mManualExposureNs = actualExposure;
                mAwbMode = awbMode;
                applyPreviewControls(mPreviewRequestBuilder);
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), new CaptureCallback(this), mCameraHandler);
            } catch (Throwable t) {
                mManualExposureEnabled = oldManual; mManualIso = oldIso;
                mManualExposureNs = oldExposure; mAwbMode = oldAwb;
                // The last accepted repeating request remains active after rejection.
                try { if (mPreviewRequestBuilder != null) applyPreviewControls(mPreviewRequestBuilder); }
                catch (Throwable ignored) { }
                String detail = t.getMessage();
                error = "Camera controls rejected: " + (detail == null ? t.getClass().getSimpleName()
                        : detail.substring(0, Math.min(detail.length(), 200)));
                Log.w(TAG, error, t);
            }
            IAuxCamCallback callback = owner;
            if (callback != null) try {
                callback.onSensorControlsResult(token,
                        new long[]{mManualExposureEnabled ? 1L : 0L, mManualIso, mManualExposureNs, mAwbMode}, error);
            } catch (RemoteException e) { Log.w(TAG, "Control result", e); }
        });
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

    private long mLastZoomStepMs;
    private final Runnable mZoomStep = new Runnable() {
        @Override public void run() {
            if (mCaptureSession == null) return;
            long now = SystemClock.elapsedRealtime();
            mZoomRatio = CameraZoom.smoothPreviewZoom(mZoomRatio, mRequestedZoom, now - mLastZoomStepMs);
            mLastZoomStepMs = now;
            recomputeCropRegion();
            refreshRepeatingRequest();
            if (mZoomRatio != mRequestedZoom) mCameraHandler.postDelayed(this, 16);
        }
    };

    @Override
    public void setZoom(final float ratio) {
        if (mCameraHandler == null) return;
        mCameraHandler.post(() -> {
            if (!Float.isFinite(ratio)) return;
            mRequestedZoom = Math.max(.6f, Math.min(10f, ratio));
            mCameraHandler.removeCallbacks(mZoomStep);
            mLastZoomStepMs = SystemClock.elapsedRealtime() - 16;
            mZoomStep.run();
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
            if (!mVideoMode && !mHighResolutionStill && CAMERA_ID.equals(mCurrentCameraId) && mDepthSurface != null) {
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
    public void triggerStill(long shutterCommandNs, ParcelFileDescriptor dngPfd) {
        triggerRawBurst(0, shutterCommandNs, new ParcelFileDescriptor[]{dngPfd});
    }

    @Override
    public void triggerRawBurst(int token, long shutterCommandNs, ParcelFileDescriptor[] outputs) {
        IAuxCamCallback owner = mCallback;
        int generation = mSessionGeneration;
        if (outputs == null) outputs = new ParcelFileDescriptor[0];
        final ParcelFileDescriptor[] destinations = outputs;
        if (mCameraHandler == null || mDestroying.get() || !mCameraHandler.post(() ->
                startRawCapture(token, shutterCommandNs, destinations, owner, generation))) {
            closeRawOutputs(destinations); rawFinished(owner, token, 0, "RAW backend unavailable");
        }
    }

    @Override
    public void cancelRawCapture(int token) {
        if (mCameraHandler != null) mCameraHandler.post(() -> {
            if (mRawJob != null && mRawJob.token == token) failRaw(mRawJob, "RAW capture cancelled");
        });
    }

    private static void closeRawOutputs(ParcelFileDescriptor[] outputs) {
        for (ParcelFileDescriptor pfd : outputs) if (pfd != null) try { pfd.close(); } catch (Exception ignored) { }
    }

    private void rawFinished(IAuxCamCallback owner, int token, int saved, String error) {
        if (owner == null || owner != mCallback) return;
        try {
            if (token == 0) {
                if (error == null) owner.onStillCaptured(); else owner.onSessionError(error);
            } else owner.onRawCaptureFinished(token, saved, error);
        } catch (RemoteException ignored) { }
    }

    private void startRawCapture(int token, long shutterCommandNs, ParcelFileDescriptor[] outputs,
            IAuxCamCallback owner, int generation) {
        RawJob job = null;
        try {
            if (generation != mSessionGeneration || owner != mCallback || mVideoMode ||
                    mStillFormat != ImageFormat.RAW_SENSOR || mRawImageReader == null ||
                    mCaptureSession == null || mCameraDevice == null || mRawJob != null || mRawWritesPending.get() != 0)
                throw new IllegalStateException("RAW session unavailable or previous capture is cleaning up");
            if (outputs.length < 1 || outputs.length > RawFrameMatcher.MAX_FRAMES ||
                    outputs.length > RawFrameMatcher.maxFramesForSize(mStillW, mStillH))
                throw new IllegalArgumentException("RAW sequence exceeds frame/memory budget (128 MiB)");
            for (ParcelFileDescriptor pfd : outputs) if (pfd == null) throw new IllegalArgumentException("Missing DNG destination");
            job = new RawJob(token, generation, RawFrameMatcher.exifOrientation(mSensorOrientation), owner,
                    cameraManager().getCameraCharacteristics(mCurrentCameraId), outputs);
            mRawJob = job;
            final RawJob active = job;
            mShutterCommandNs = shutterCommandNs;
            Image stale;
            while ((stale = mRawImageReader.acquireNextImage()) != null) stale.close();
            mCameraHandler.removeCallbacks(mZoomStep);
            mZoomRatio = mRequestedZoom;
            recomputeCropRegion();
            ArrayList<CaptureRequest> requests = new ArrayList<>();
            for (int index = 0; index < outputs.length; index++) {
                CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(mStillSurface);
                b.setTag(index);
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_ENABLE_ZSL, false);
                b.set(CaptureRequest.CONTROL_AF_MODE, mManualFocusActive ? CaptureRequest.CONTROL_AF_MODE_OFF :
                        mAfLocked ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                if (mManualFocusActive) b.set(CaptureRequest.LENS_FOCUS_DISTANCE, mManualFocusDistance);
                if (mAfLocked) b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                if (mAfRegion != null) b.set(CaptureRequest.CONTROL_AF_REGIONS,
                        new MeteringRectangle[]{new MeteringRectangle(mAfRegion, MeteringRectangle.METERING_WEIGHT_MAX)});
                applyCropAndExposure(b, false);
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                applySensorControls(b, true);
                applyMinimalProcessing(b);
                requests.add(b.build());
            }
            job.timeout = () -> failRaw(active, "RAW capture timed out");
            mCameraHandler.postDelayed(job.timeout, 20_000L);
            mCaptureSession.captureBurst(requests, new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    if (active != mRawJob || active.generation != mSessionGeneration || session != mCaptureSession) return;
                    try {
                        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                        if (timestamp == null) throw new IllegalStateException("RAW result has no sensor timestamp");
                        writeMatchedRaw(active, active.matcher.result(timestamp, (Integer) request.getTag(), result));
                    } catch (Exception e) { failRaw(active, "RAW result rejected: " + e.getClass().getSimpleName()); }
                }
                @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    failRaw(active, "RAW frame failed: HAL reason=" + failure.getReason());
                }
                @Override public void onCaptureSequenceAborted(CameraCaptureSession session, int sequenceId) {
                    failRaw(active, "RAW capture sequence aborted");
                }
            }, mCameraHandler);
        } catch (Exception e) {
            if (job != null) failRaw(job, "RAW capture failed: " + e.getClass().getSimpleName());
            else { closeRawOutputs(outputs); rawFinished(owner, token, 0, "RAW capture rejected: " + e.getMessage()); }
        }
    }

    private void onRawImageAvailable(ImageReader reader) {
        try {
            Image image;
            while ((image = reader.acquireNextImage()) != null) {
                RawJob job = mRawJob;
                if (reader != mRawImageReader || job == null || job.cancelled.get()) { image.close(); continue; }
                boolean transferred = false;
                try {
                    long timestamp = image.getTimestamp();
                    long bytes = image.getPlanes()[0].getBuffer().remaining();
                    // Compute metadata before handing off; even plane access can fail.
                    transferred = true; // matcher.image owns distinct acquisitions on rejection too.
                    writeMatchedRaw(job, job.matcher.image(timestamp, bytes, image));
                } catch (Exception e) { failRaw(job, "RAW image rejected: " + e.getClass().getSimpleName()); }
                finally { if (!transferred) try { image.close(); } catch (Exception ignored) { } }
            }
        } catch (Exception e) {
            if (reader == mRawImageReader && mRawJob != null) failRaw(mRawJob, "RAW reader failed");
        }
    }

    private void writeMatchedRaw(RawJob job, RawFrameMatcher.Match<Image, TotalCaptureResult> pair) {
        if (pair == null) return;
        ParcelFileDescriptor pfd = job.outputs[pair.index];
        job.outputs[pair.index] = null; // Writer alone owns this descriptor and Image now.
        mRawWritesPending.incrementAndGet();
        Runnable write = () -> {
            String failure = null;
            try {
                if (!job.cancelled.get()) {
                    try (DngCreator dng = new DngCreator(job.characteristics, pair.result);
                            ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(pfd)) {
                        dng.setOrientation(job.orientation);
                        dng.writeImage(stream, pair.image);
                        stream.flush();
                    }
                }
            } catch (Exception e) { failure = "DNG write failed: " + e.getClass().getSimpleName(); }
            finally {
                try { pair.image.close(); } catch (Exception ignored) { }
                try { pfd.close(); } catch (Exception ignored) { }
                mRawWritesPending.decrementAndGet();
            }
            final String error = failure;
            mCameraHandler.post(() -> {
                if (mRawJob != job || job.generation != mSessionGeneration || job.cancelled.get()) return;
                if (error != null) { failRaw(job, error); return; }
                if (++job.saved == job.outputs.length) {
                    mCameraHandler.removeCallbacks(job.timeout);
                    job.matcher.close(); mRawJob = null;
                    rawFinished(job.owner, job.token, job.saved, null);
                }
            });
        };
        try { mRawWriter.execute(write); }
        catch (java.util.concurrent.RejectedExecutionException rejected) {
            mRawWritesPending.decrementAndGet();
            try { pair.image.close(); } catch (Exception ignored) { }
            try { pfd.close(); } catch (Exception ignored) { }
            failRaw(job, "RAW writer unavailable");
        }
    }

    private void failRaw(RawJob job, String error) {
        if (mRawJob != job) return;
        mRawJob = null;
        job.cancelled.set(true);
        if (job.timeout != null) mCameraHandler.removeCallbacks(job.timeout);
        job.matcher.close(); closeRawOutputs(job.outputs);
        try { if (mCaptureSession != null) mCaptureSession.abortCaptures(); } catch (Exception ignored) { }
        rawFinished(job.owner, job.token, job.saved, error);
    }

    public void triggerStill(long shutterCommandNs, boolean highQualityNr) {
        triggerStillWithZsl(shutterCommandNs, highQualityNr, true);
    }

    @Override
    public void triggerStillWithZsl(long shutterCommandNs, boolean highQualityNr, boolean enableZsl) {
        if (mCameraHandler != null) mCameraHandler.post(() ->
                captureStillOnCameraThread(shutterCommandNs, enableZsl));
    }

    private void captureStillOnCameraThread(long shutterCommandNs, boolean enableZsl) {
        if (mStillFormat == ImageFormat.RAW_SENSOR) {
            notifyError("RAW capture requires DNG destinations"); return;
        }
        if (mVideoMode || mCameraDevice == null || mCaptureSession == null || mStillSurface == null) {
            Log.e(TAG, "Cannot trigger still capture: session not ready");
            notifyError("Session not ready for still capture");
            return;
        }

        mShutterCommandNs = (shutterCommandNs > 0) ? shutterCommandNs : SystemClock.elapsedRealtimeNanos();
        Log.i("TIMING_FREEZE", "capture_request_submitted: " + SystemClock.elapsedRealtimeNanos() + " ns");
        Log.i(TAG, "TIMING t0 (shutter_command): " + mShutterCommandNs + " ns. Dispatching STILL_CAPTURE request (minimalProcessing=true, enableZsl=" + enableZsl + ")...");
        try {
            // A full-quality still uses the final requested crop, never an in-between animation step.
            mCameraHandler.removeCallbacks(mZoomStep);
            mZoomRatio = mRequestedZoom;
            recomputeCropRegion();
            refreshRepeatingRequest();
            CaptureRequest.Builder stillBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            stillBuilder.addTarget(mStillSurface);
            stillBuilder.set(CaptureRequest.CONTROL_ENABLE_ZSL, enableZsl && !mManualExposureEnabled && mStillFormat != ImageFormat.JPEG);
            if (mStillFormat == ImageFormat.JPEG) {
                if (android.os.Build.VERSION.SDK_INT >= 31 && (mHighResolutionTarget == null || !"Nothing camera 50 MP stream map".equals(mHighResolutionTarget.source))) {
                    stillBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE, mStillPixelMode);
                }
                if (mHighResolutionStill) {
                    try {
                        stillBuilder.set(new CaptureRequest.Key<Integer>("com.nothing.camera.remosaic.enable", Integer.class), mRemosaicStatus == 1 ? 1 : 0);
                    } catch (Throwable ignored) {}
                }
                stillBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
                stillBuilder.set(CaptureRequest.JPEG_ORIENTATION, mSensorOrientation);
            }
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
            applyCropAndExposure(stillBuilder, mHighResolutionStill);
            applyStillFlash(stillBuilder);
            applySensorControls(stillBuilder, true);
            if (mHighResolutionStill && mRemosaicStatus == 1) {
                stillBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);
            } else {
                applyMinimalProcessing(stillBuilder);
            }

            mCaptureSession.capture(stillBuilder.build(), new StillCaptureCallback(this), mCameraHandler);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to dispatch still capture request", t);
            notifyError("Still capture failed: " + t.getMessage());
        }
    }

    private static boolean supports(int[] modes, int mode) {
        if (modes != null) for (int available : modes) if (available == mode) return true;
        return false;
    }

    // YUV is still ISP processed, not Bayer RAW. Request the least additional
    // processing advertised by the HAL, without sending unsupported OFF modes.
    private void applyMinimalProcessing(CaptureRequest.Builder b) {
        if (supports(mNoiseModes, CaptureRequest.NOISE_REDUCTION_MODE_OFF))
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        else if (supports(mNoiseModes, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL))
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL);
        if (supports(mEdgeModes, CaptureRequest.EDGE_MODE_OFF))
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
    }

    void onStillCompleted() {
        Log.i("TIMING_FREEZE", "capture_session_completed: " + SystemClock.elapsedRealtimeNanos() + " ns");
        Log.i(TAG, "Still capture completed successfully without interrupting viewfinder stream!");
        IAuxCamCallback callback = mCallback;
        if (callback != null) {
            try {
                callback.onStillCaptured();
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception notifying onStillCaptured", e);
            }
        }
    }

    void onCaptureResult(TotalCaptureResult result) {
        mColorFrameCount++;
        mDepthFrameCount++; // Synced hardware DPE frames

        try {
            Integer status = result.get(new CaptureResult.Key<Integer>("com.nothing.camera.remosaic.status", Integer.class));
            if (status != null) mRemosaicStatus = status;
        } catch (Throwable ignored) {}

        long nowCadence = SystemClock.elapsedRealtime();
        if (nowCadence - mLastCadenceLogMs >= 1000) {
            mLastCadenceLogMs = nowCadence;

            Long frameDurNs = result.get(CaptureResult.SENSOR_FRAME_DURATION);
            Long expoNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            android.util.Range<Integer> targetFpsRange = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE);

            Log.i(TAG, String.format(java.util.Locale.US,
                "SENSOR_CADENCE: frameDur=%.2fms expo=%.2fms iso=%d aeState=%d targetFpsRange=[%d,%d] lux=n/a",
                (frameDurNs != null ? frameDurNs / 1e6 : 0.0),
                (expoNs != null ? expoNs / 1e6 : 0.0),
                (iso != null ? iso : 0),
                (aeState != null ? aeState : -1),
                (targetFpsRange != null ? targetFpsRange.getLower() : 0),
                (targetFpsRange != null ? targetFpsRange.getUpper() : 0)));

            Float focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            android.hardware.camera2.params.MeteringRectangle[] afRegions = result.get(CaptureResult.CONTROL_AF_REGIONS);
            float s1Hal = (focusDistanceDiopters != null && focusDistanceDiopters > 0.001f) ? (1.0f / focusDistanceDiopters) : 0.0f;
            float s1Matte = sLatestS1Matte;
            String afStateStr = "INACTIVE";
            if (afState != null) {
                if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
                    afStateStr = "LOCKED";
                } else if (afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN) {
                    afStateStr = "SCANNING";
                } else if (afState == CaptureResult.CONTROL_AF_STATE_INACTIVE) {
                    afStateStr = "INACTIVE";
                } else {
                    afStateStr = "STATIONARY";
                }
            }
            int inFlag = ((afStateStr.equals("LOCKED") || afStateStr.equals("STATIONARY")) && s1Hal > 0.05f && s1Hal < 20.0f) ? 1 : 0;
            int rx0 = 0, ry0 = 0, rx1 = 0, ry1 = 0;
            if (afRegions != null && afRegions.length > 0 && afRegions[0] != null) {
                android.graphics.Rect r = afRegions[0].getRect();
                rx0 = r.left; ry0 = r.top; rx1 = r.right; ry1 = r.bottom;
            }
            Log.i(TAG, String.format(java.util.Locale.US,
                "AF_META: s1_hal=%.2fm s1_matte=%.2fm state=%s afRegion=[%d,%d,%d,%d] in=%d",
                s1Hal, s1Matte, afStateStr, rx0, ry0, rx1, ry1, inFlag));
        }

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

            IAuxCamCallback callback = mCallback;
            if (callback != null) {
                try {
                    callback.onStats(colorFps, depthFps, dtP99);
                } catch (RemoteException e) {
                    // Client disconnected
                }
            }
        }
    }

    @Override
    public void closeSession() {
        if (mCameraHandler == null) return;
        if (android.os.Looper.myLooper() == mCameraHandler.getLooper()) {
            closeSessionOnCameraThread();
            return;
        }
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        mCameraHandler.post(() -> {
            try { closeSessionOnCameraThread(); } finally { closed.countDown(); }
        });
        try {
            if (!closed.await(3, java.util.concurrent.TimeUnit.SECONDS)) Log.w(TAG, "Camera close timed out");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void closeSessionOnCameraThread() {
        mSensorControls = new SensorControlCapabilities();
        mSessionGeneration++; // Stale onOpened/onConfigured callbacks close themselves.
        if (mCameraHandler != null) {
            mCameraHandler.removeCallbacks(mZoomStep);
            mCameraHandler.removeCallbacks(mAfResetRunnable);
        }
        CameraCaptureSession session = mCaptureSession;
        CameraDevice camera = mCameraDevice;
        mCaptureSession = null;
        mCameraDevice = null;
        mPreviewRequestBuilder = null;
        if (mRawJob != null) failRaw(mRawJob, "RAW session closed");
        if (mRawImageReader != null) {
            ImageReader retired = mRawImageReader;
            mRawImageReader = null;
            retired.setOnImageAvailableListener(null, null);
            // Closing a reader invalidates acquired Images: retire it only AFTER writer jobs.
            mRawWriter.execute(() -> { try { retired.close(); } catch (Exception ignored) { } });
        }
        Surface[] surfaces = {mColorSurface, mDepthSurface, mStillSurface, mRecordingSurface};
        mColorSurface = null; mDepthSurface = null; mStillSurface = null; mRecordingSurface = null;
        mAfRegion = null; mCropRegion = null;
        mActiveArray = null; mMaximumActiveArray = null; mCompensationRange = null;
        mPreviewFpsRange = null; mRotateAndCropModes = null; mNoiseModes = null; mEdgeModes = null;
        // Each step is independent: session.close throwing must not leak camera.
        if (session != null) {
            try { session.stopRepeating(); } catch (Exception ignored) { }
            try { session.abortCaptures(); } catch (Exception ignored) { }
            try { session.close(); } catch (Exception e) { Log.w(TAG, "Session close failed", e); }
        }
        if (camera != null) try { camera.close(); } catch (Exception e) { Log.w(TAG, "Camera close failed", e); }
        // These are this remote process's parcelled Surface references, not the
        // client's SurfaceView/MediaRecorder objects. Release each once.
        java.util.HashSet<Surface> released = new java.util.HashSet<>();
        for (Surface surface : surfaces) if (surface != null && released.add(surface)) {
            try { surface.release(); } catch (Exception ignored) { }
        }
    }

    private void notifyError(String msg) {
        IAuxCamCallback callback = mCallback;
        if (callback != null) {
            try {
                callback.onSessionError(msg);
            } catch (RemoteException e) {
                Log.w(TAG, "Error notifying client", e);
            }
        }
    }

    static class DeviceStateCallback extends CameraDevice.StateCallback {
        private final CameraUserService mService;
        private final String mColorPhysicalId;
        private final int mGeneration;

        DeviceStateCallback(CameraUserService service, String colorPhysicalId) {
            mService = service;
            mColorPhysicalId = colorPhysicalId;
            mGeneration = service.mSessionGeneration;
        }

        @Override
        public void onOpened(CameraDevice camera) {
            if (mGeneration != mService.mSessionGeneration) { camera.close(); return; }
            mService.onDeviceOpened(camera, mColorPhysicalId);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            if (mGeneration != mService.mSessionGeneration) { camera.close(); return; }
            Log.w(TAG, "Camera disconnected");
            try { camera.close(); } catch (Throwable ignored) {}
            mService.closeSessionOnCameraThread();
            mService.notifyError("Camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            if (mGeneration != mService.mSessionGeneration) { camera.close(); return; }
            Log.e(TAG, "Camera device error on " + mService.mCurrentCameraId + ": " + error);
            try { camera.close(); } catch (Throwable ignored) {}
            mService.closeSessionOnCameraThread();
            mService.notifyError("Camera error code: " + error);
        }
    }

    static class SessionStateCallback extends CameraCaptureSession.StateCallback {
        private final CameraUserService mService;
        private final int mGeneration;

        SessionStateCallback(CameraUserService service) {
            mService = service;
            mGeneration = service.mSessionGeneration;
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            if (mGeneration != mService.mSessionGeneration) { session.close(); return; }
            mService.onSessionConfigured(session);
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            if (mGeneration != mService.mSessionGeneration) { session.close(); return; }
            Log.e(TAG, "Session configuration failed on Camera " + mService.mCurrentCameraId);
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
            if (session == mService.mCaptureSession) mService.onCaptureResult(result);
        }
    }

    static class StillCaptureCallback extends CameraCaptureSession.CaptureCallback {
        private final CameraUserService mService;
        private final int mGeneration;

        StillCaptureCallback(CameraUserService service) {
            mService = service;
            mGeneration = service.mSessionGeneration;
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
            if (mGeneration != mService.mSessionGeneration || session != mService.mCaptureSession) return;
            long t_hal = SystemClock.elapsedRealtimeNanos();
            long dt = (t_hal - mService.mShutterCommandNs) / 1_000_000;
            Long sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP);
            long sensorDeltaMs = (sensorTs != null) ? (sensorTs - mService.mShutterCommandNs) / 1_000_000 : 0;
            Log.i(TAG, "TIMING HAL onCaptureCompleted: " + t_hal + " ns, total HAL dt=" + dt + " ms, sensorTs=" + sensorTs + " ns, sensor_minus_t0=" + sensorDeltaMs + " ms");
            mService.onStillCompleted();
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            if (mGeneration != mService.mSessionGeneration || session != mService.mCaptureSession) return;
            Log.e(TAG, "TIMING Still onCaptureFailed: reason=" + failure.getReason() + ", frameNumber=" + failure.getFrameNumber());
            mService.notifyError("Still capture failed: HAL reason=" + failure.getReason());
        }

        @Override
        public void onCaptureSequenceAborted(CameraCaptureSession session, int sequenceId) {
            if (mGeneration != mService.mSessionGeneration || session != mService.mCaptureSession) return;
            mService.notifyError("Still capture failed: request aborted");
        }
    }
}
