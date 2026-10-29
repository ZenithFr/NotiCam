package com.zenith.auxcam.probe;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.*;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class DepthProbeService extends Service {
    public static final String TAG = "DepthProbe";
    public static final String OUT = "/data/local/tmp/depth_probe/";

    private static CameraDevice sCam;
    private static CameraCaptureSession sSess;
    private static ImageReader sColorIr;
    private static ImageReader sDepthIr;
    private static ImageReader sStillIr;
    private static HandlerThread sThread;
    private static Handler sHandler;
    private static CameraManager sCameraManager;
    private static Context sShellContext;

    private static int sColorCount = 0;
    private static int sDepthCount = 0;
    private static final CountDownLatch sLatch = new CountDownLatch(1);
    private static boolean sDepthGatePassed = false;

    // G-F Autofocus probe storage
    private static Float sLastFocusDistance = null;
    private static Integer sFocusCalibration = null;

    // Direct executor for SessionConfiguration
    static class DirectExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            if (sHandler != null) {
                sHandler.post(command);
            } else {
                command.run();
            }
        }
    }

    static class ColorAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                int count = sColorCount++;
                if (count < 5 || count % 10 == 0) {
                    dumpOne(img, "color_" + count, true);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Color image acquire error", t);
            } finally {
                if (img != null) img.close();
            }
        }
    }

    static class DepthAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                int count = sDepthCount++;
                dumpOne(img, "depth_" + count, false);
                if (count >= 30 && !sDepthGatePassed) {
                    sDepthGatePassed = true;
                    Log.i(TAG, "=======================================================");
                    Log.i(TAG, "GOT 30 DEPTH FRAMES — G-A PASS");
                    Log.i(TAG, "=======================================================");
                    sLatch.countDown();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Depth image acquire error", t);
            } finally {
                if (img != null) img.close();
            }
        }
    }

    static class StillAvailableListener implements ImageReader.OnImageAvailableListener {
        private int mStillCount = 0;
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                int count = mStillCount++;
                Log.i(TAG, "=======================================================");
                Log.i(TAG, "[TASK 5 PASS] still #" + count + " " + img.getWidth() + "x" + img.getHeight()
                        + " fmt=" + img.getFormat() + " ts=" + img.getTimestamp());
                Log.i(TAG, "=======================================================");
                System.out.println("[TASK 5 PASS] still #" + count + " " + img.getWidth() + "x" + img.getHeight()
                        + " fmt=" + img.getFormat() + " ts=" + img.getTimestamp());
                sLatch.countDown();
            } catch (Throwable t) {
                Log.e(TAG, "Still image acquire error", t);
            } finally {
                if (img != null) img.close();
            }
        }
    }

    private static String sComboArg = null;

    public static void main(String[] args) {
        System.out.println("[DepthProbe] Starting standalone runner under UID=" + android.os.Process.myUid());
        if (args != null && args.length > 0) {
            sComboArg = args[0];
        }
        setupContextAndLooper();
        runProbeInternal(sComboArg != null);
        System.out.println("[DepthProbe] Finished probe execution.");
    }

    static class AsyncComboRunner implements Runnable {
        private final CameraDevice mCam;
        private final String mMode;
        public AsyncComboRunner(CameraDevice cam, String mode) {
            mCam = cam;
            mMode = mode;
        }
        @Override
        public void run() {
            runSingleComboProbe(mCam, mMode);
        }
    }

    static class CameraStateCb extends CameraDevice.StateCallback {
        private final boolean mRunStreamComboTest;

        public CameraStateCb(boolean runStreamComboTest) {
            mRunStreamComboTest = runStreamComboTest;
        }

        @Override
        public void onOpened(CameraDevice camera) {
            Log.i(TAG, "Camera 5 onOpened successfully! Device=" + camera.getId());
            sCam = camera;
            if (mRunStreamComboTest) {
                new Thread(new AsyncComboRunner(camera, sComboArg)).start();
            } else {
                configureSession(camera);
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.w(TAG, "Camera 5 onDisconnected");
            sCam = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "Camera 5 onError: " + error);
            sCam = null;
            sLatch.countDown();
        }
    }

    static class StillCaptureTask implements Runnable {
        private final CameraDevice mCam;
        private final CameraCaptureSession mSession;
        public StillCaptureTask(CameraDevice cam, CameraCaptureSession session) {
            mCam = cam;
            mSession = session;
        }
        @Override
        public void run() {
            try {
                CaptureRequest.Builder sb = mCam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                sb.addTarget(sStillIr.getSurface());
                mSession.capture(sb.build(), null, sHandler);
                Log.i(TAG, "Dispatched Task 5 still capture request for 4080x3072 YUV");
            } catch (Throwable t) {
                Log.e(TAG, "Failed still capture request", t);
            }
        }
    }

    static class CaptureSessionCb extends CameraCaptureSession.StateCallback {
        private final CameraDevice mCam;

        public CaptureSessionCb(CameraDevice cam) {
            mCam = cam;
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            Log.i(TAG, "CaptureSession configured successfully! G-A session combo active.");
            sSess = session;
            try {
                CaptureRequest.Builder b = mCam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(sColorIr.getSurface());
                b.addTarget(sDepthIr.getSurface());

                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                // Apply MediaTek & Nothing vendor keys for stereo disparity
                tryVendorKey(b, "com.mediatek.stereofeature.enable", (byte) 1);
                tryVendorKey(b, "com.mediatek.stereofeature.depth.enable", (byte) 1);
                tryVendorKey(b, "com.mediatek.vsdoffeature.vsdofPreviewEnable", (byte) 1);
                tryVendorKey(b, "com.mediatek.vsdoffeature.vsdofFeaturePreviewMode", 1);
                tryVendorKey(b, "com.mediatek.vsdoffeature.vsdofShotMode", (byte) 1);

                session.setRepeatingRequest(b.build(), new CaptureResultCb(), sHandler);
                Log.i(TAG, "Repeating request started for Color + Depth (DPE)");

                if (sStillIr != null) {
                    sHandler.postDelayed(new StillCaptureTask(mCam, session), 400);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed setting repeating request", t);
                sLatch.countDown();
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "CONFIGURE FAILED — G-A / session combo blocked");
            sLatch.countDown();
        }
    }

    static class CaptureResultCb extends CameraCaptureSession.CaptureCallback {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            // G-F Autofocus probe
            Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (fd != null && sLastFocusDistance == null) {
                sLastFocusDistance = fd;
                Log.i(TAG, "[G-F PROBE] focusDistDiopters=" + fd + " cal=" + sFocusCalibration);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "DepthProbeService onCreate, UID=" + android.os.Process.myUid());
        setupContextAndLooper();
        new Thread(new ProbeRunner(false)).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static class ProbeRunner implements Runnable {
        private final boolean mRunCombos;
        public ProbeRunner(boolean runCombos) { mRunCombos = runCombos; }
        @Override
        public void run() {
            runProbeInternal(mRunCombos);
        }
    }

    public static void runProbeInternal(boolean runCombos) {
        File outDir = new File(OUT);
        if (!outDir.exists()) outDir.mkdirs();

        try {
            openCamera5(runCombos);
            // Wait for 30 frames or 20s timeout
            boolean finished = sLatch.await(20, TimeUnit.SECONDS);
            Log.i(TAG, "Probe wait finished. Success=" + sDepthGatePassed + " frames=" + sDepthCount);
        } catch (Throwable t) {
            Log.e(TAG, "Depth probe top-level error", t);
        } finally {
            closeSession();
        }
    }

    static class FalseSupplier implements java.util.function.BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return false;
        }
    }

    private static void setupContextAndLooper() {
        try {
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }
            disableDesktopCompatFlags();

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object at = activityThreadClass.getMethod("systemMain").invoke(null);
            Context systemContext = (Context) activityThreadClass.getMethod("getSystemContext").invoke(at);
            fixAttribution(systemContext, "com.android.shell", 2000);

            sShellContext = systemContext.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            fixAttribution(sShellContext, "com.android.shell", 2000);

            try {
                Context ntContext = systemContext.createPackageContext("com.nothing.camera", Context.CONTEXT_IGNORE_SECURITY);
                fixAttribution(ntContext, "com.nothing.camera", 2000);

                android.app.Application fakeApp = new android.app.Application();
                Field baseField = android.content.ContextWrapper.class.getDeclaredField("mBase");
                baseField.setAccessible(true);
                baseField.set(fakeApp, ntContext);

                Field initAppField = activityThreadClass.getDeclaredField("mInitialApplication");
                initAppField.setAccessible(true);
                initAppField.set(at, fakeApp);
                Log.i(TAG, "Spoofed opPackageName: " + activityThreadClass.getMethod("currentOpPackageName").invoke(null));
            } catch (Throwable t) {
                Log.w(TAG, "Failed spoofing opPackageName: " + t);
            }

            sCameraManager = (CameraManager) sShellContext.getSystemService(Context.CAMERA_SERVICE);

            if (sThread == null) {
                sThread = new HandlerThread("DepthProbeBg");
                sThread.start();
                sHandler = new Handler(sThread.getLooper());
            }
        } catch (Throwable t) {
            Log.e(TAG, "Context setup failed", t);
        }
    }

    private static void disableDesktopCompatFlags() {
        try {
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
            Log.w(TAG, "disableDesktopCompatFlags note: " + t);
        }
    }

    private static void fixAttribution(Context ctx, String pkg, int uid) throws Exception {
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
        }
    }

    private static void openCamera5(boolean runCombos) throws Exception {
        CameraCharacteristics c5 = sCameraManager.getCameraCharacteristics("5");
        dumpCharacteristics(c5, "5");

        // G-F: Focus calibration dump
        sFocusCalibration = c5.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        Log.i(TAG, "[G-F PROBE] Camera 5 LENS_INFO_FOCUS_DISTANCE_CALIBRATION=" + sFocusCalibration);

        CameraCharacteristics c0 = sCameraManager.getCameraCharacteristics("0");
        Integer c0Cal = c0.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        Log.i(TAG, "[G-F PROBE] Camera 0 LENS_INFO_FOCUS_DISTANCE_CALIBRATION=" + c0Cal);

        if (runCombos && !"depth512".equalsIgnoreCase(sComboArg) && !"still_yuv".equalsIgnoreCase(sComboArg)) {
            sCameraManager.openCamera("5", new CameraStateCb(true), sHandler);
            return;
        }

        boolean isDepth512 = "depth512".equalsIgnoreCase(sComboArg);
        boolean isStillYuvTest = "still_yuv".equalsIgnoreCase(sComboArg);

        int depthW = isDepth512 ? 512 : 448;
        int depthH = isDepth512 ? 384 : 288;

        // Two readers: Color (1440x1080 YUV) + Depth
        // For Task 5 test: Color preview can also test GPU-only usage
        long colorUsage = isStillYuvTest ? HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE :
                (HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_CPU_READ_OFTEN);

        sColorIr = ImageReader.newInstance(1440, 1080, ImageFormat.YUV_420_888, 5, colorUsage);
        sColorIr.setOnImageAvailableListener(new ColorAvailableListener(), sHandler);

        // Native DPE hardware disparity map: 448x288 or 512x384 Y8
        int y8Format = ImageFormat.Y8; // 538982489 (0x20203859)
        sDepthIr = ImageReader.newInstance(depthW, depthH, y8Format, 5,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_CPU_READ_OFTEN);
        sDepthIr.setOnImageAvailableListener(new DepthAvailableListener(), sHandler);

        if (isStillYuvTest) {
            // Task 5: Still stream at exactly 4080x3072 YUV (GPU-only usage)
            sStillIr = ImageReader.newInstance(4080, 3072, ImageFormat.YUV_420_888, 2,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            sStillIr.setOnImageAvailableListener(new StillAvailableListener(), sHandler);
            Log.i(TAG, "Task 5 Still Ir configured: 4080x3072 YUV GPU-only");
        }

        Log.i(TAG, "Opening Camera 5 with Color=1440x1080 YUV, Depth=" + depthW + "x" + depthH + " Y8, StillTest=" + isStillYuvTest);
        sCameraManager.openCamera("5", new CameraStateCb(false), sHandler);
    }

    private static void configureSession(CameraDevice camera) {
        try {
            List<OutputConfiguration> outs = new ArrayList<>();
            OutputConfiguration co = new OutputConfiguration(sColorIr.getSurface());
            co.setPhysicalCameraId("0");

            // Depth surface has NO physical camera ID set (originates from logical DPE)
            OutputConfiguration dco = new OutputConfiguration(sDepthIr.getSurface());

            outs.add(co);
            outs.add(dco);

            if (sStillIr != null) {
                OutputConfiguration so = new OutputConfiguration(sStillIr.getSurface());
                so.setPhysicalCameraId("0");
                outs.add(so);
            }

            SessionConfiguration sc = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outs,
                    new DirectExecutor(),
                    new CaptureSessionCb(camera)
            );
            camera.createCaptureSession(sc);
        } catch (Throwable t) {
            Log.e(TAG, "configureSession failed", t);
            sLatch.countDown();
        }
    }

    private static void runSingleComboProbe(CameraDevice camera, String mode) {
        Log.i(TAG, "=== Running G-E Combo Probe: " + mode + " ===");
        if ("combo1".equalsIgnoreCase(mode)) {
            testCombo(camera, "Combo 1 (Preview 1440x1080 + Depth 448x288)", 1440, 1080, 448, 288, 0, 0, false);
        } else if ("combo2".equalsIgnoreCase(mode)) {
            testCombo(camera, "Combo 2 (Still 4080x3072 + Depth 448x288)", 4080, 3072, 448, 288, 0, 0, false);
        } else if ("combo3".equalsIgnoreCase(mode)) {
            testCombo(camera, "Combo 3 (Preview 1440x1080 + Depth 448x288 + Still 4080x3072 JPEG)", 1440, 1080, 448, 288, 4080, 3072, false);
        } else if ("combo4".equalsIgnoreCase(mode)) {
            testCombo(camera, "Combo 4 (Preview 1440x1080 + Depth 448x288 + Still 4080x3072 YUV)", 1440, 1080, 448, 288, 4080, 3072, true);
        } else {
            Log.w(TAG, "Unknown combo mode: " + mode);
        }
        sLatch.countDown();
    }

    static class ComboSessionCb extends CameraCaptureSession.StateCallback {
        private final boolean[] mOk;
        private final String[] mErrorMsg;
        private final CountDownLatch mLatch;

        public ComboSessionCb(boolean[] ok, String[] errorMsg, CountDownLatch latch) {
            mOk = ok;
            mErrorMsg = errorMsg;
            mLatch = latch;
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            mOk[0] = true;
            mLatch.countDown();
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            mOk[0] = false;
            mErrorMsg[0] = "onConfigureFailed";
            mLatch.countDown();
        }
    }

    private static void testCombo(CameraDevice camera, String name, int cW, int cH, int dW, int dH, int sW, int sH, boolean stillIsYuv) {
        ImageReader cIr = null, dIr = null, sIr = null;
        try {
            List<OutputConfiguration> outs = new ArrayList<>();
            cIr = ImageReader.newInstance(cW, cH, ImageFormat.YUV_420_888, 2);
            OutputConfiguration co = new OutputConfiguration(cIr.getSurface());
            co.setPhysicalCameraId("0");
            outs.add(co);

            dIr = ImageReader.newInstance(dW, dH, ImageFormat.Y8, 2);
            OutputConfiguration dco = new OutputConfiguration(dIr.getSurface());
            outs.add(dco);

            if (sW > 0 && sH > 0) {
                int fmt = stillIsYuv ? ImageFormat.YUV_420_888 : ImageFormat.JPEG;
                sIr = ImageReader.newInstance(sW, sH, fmt, 2);
                OutputConfiguration so = new OutputConfiguration(sIr.getSurface());
                so.setPhysicalCameraId("0");
                outs.add(so);
            }

            final CountDownLatch sessionLatch = new CountDownLatch(1);
            final boolean[] ok = new boolean[]{false};
            final String[] errorMsg = new String[]{""};
            ComboSessionCb cb = new ComboSessionCb(ok, errorMsg, sessionLatch);

            SessionConfiguration sc = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outs,
                    new DirectExecutor(),
                    cb
            );
            camera.createCaptureSession(sc);
            sessionLatch.await(5, TimeUnit.SECONDS);

            if (ok[0]) {
                Log.i(TAG, "=======================================================");
                Log.i(TAG, "[G-E RESULT] " + name + ": SESSION_OK");
                Log.i(TAG, "=======================================================");
                System.out.println("[G-E RESULT] " + name + ": SESSION_OK");
            } else {
                Log.e(TAG, "=======================================================");
                Log.e(TAG, "[G-E RESULT] " + name + ": SESSION_FAIL (" + errorMsg[0] + ")");
                Log.e(TAG, "=======================================================");
                System.out.println("[G-E RESULT] " + name + ": SESSION_FAIL (" + errorMsg[0] + ")");
            }
        } catch (Throwable t) {
            Log.e(TAG, "[G-E RESULT] " + name + ": SESSION_FAIL exception: " + t.getMessage());
            System.out.println("[G-E RESULT] " + name + ": SESSION_FAIL exception: " + t.getMessage());
        }
    }

    private static void tryVendorKey(CaptureRequest.Builder b, String name, Object val) {
        try {
            CameraCharacteristics c = sCameraManager.getCameraCharacteristics("5");
            List<CaptureRequest.Key<?>> keys = c.getAvailableCaptureRequestKeys();
            for (CaptureRequest.Key<?> k : keys) {
                if (k.getName().equals(name)) {
                    b.set((CaptureRequest.Key) k, val);
                    Log.i(TAG, "Set vendor key " + name + " = " + val);
                    return;
                }
            }
            Log.w(TAG, "Vendor key not found: " + name);
        } catch (Throwable t) {
            Log.w(TAG, "Vendor key fail " + name + ": " + t.getMessage());
        }
    }

    private static void dumpCharacteristics(CameraCharacteristics c, String id) {
        Integer syncType = c.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE);
        String syncStr = (syncType == null) ? "null" : (syncType == 0 ? "APPROXIMATE" : "CALIBRATED");
        Log.i(TAG, "cam" + id + " syncType=" + syncStr + " (" + syncType + ")");

        List<CaptureRequest.Key<?>> keys = c.getAvailableCaptureRequestKeys();
        List<String> vendorList = new ArrayList<>();
        for (CaptureRequest.Key<?> k : keys) {
            String name = k.getName();
            if (name.contains("mediatek") || name.contains("nothing")) {
                vendorList.add(name);
            }
        }
        Log.i(TAG, "cam" + id + " vendor keys count=" + vendorList.size() + ": " + vendorList);
    }

    private static void dumpOne(Image img, String name, boolean color) {
        try {
            HardwareBuffer hb = img.getHardwareBuffer();
            String ahbStr = (hb != null) ? (hb.getWidth() + "x" + hb.getHeight()
                    + " fmt=0x" + Integer.toHexString(hb.getFormat())
                    + " usage=0x" + Long.toHexString(hb.getUsage())) : "null";

            Log.i(TAG, name + " fmt=" + img.getFormat()
                    + " " + img.getWidth() + "x" + img.getHeight()
                    + " ts=" + img.getTimestamp()
                    + " ahb=[" + ahbStr + "]");

            Image.Plane[] planes = img.getPlanes();
            if (planes != null && planes.length > 0) {
                ByteBuffer p0 = planes[0].getBuffer();
                int size = p0.remaining();
                File f = new File(OUT + name + ".bin");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    byte[] buf = new byte[size];
                    p0.duplicate().get(buf);
                    fos.write(buf);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "dumpOne error for " + name, t);
        }
    }

    private static void closeSession() {
        try {
            if (sSess != null) {
                sSess.close();
                sSess = null;
            }
            if (sCam != null) {
                sCam.close();
                sCam = null;
            }
            if (sColorIr != null) {
                sColorIr.close();
                sColorIr = null;
            }
            if (sDepthIr != null) {
                sDepthIr.close();
                sDepthIr = null;
            }
            Log.i(TAG, "DepthProbe resources cleaned up.");
        } catch (Throwable t) {
            Log.e(TAG, "Error closing probe session", t);
        }
    }
}
