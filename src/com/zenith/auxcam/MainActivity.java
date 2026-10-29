package com.zenith.auxcam;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity implements SurfaceHolder.Callback, View.OnClickListener {
    private static final String TAG = "AuxCamActivity";
    private static final int SHIZUKU_REQUEST_CODE = 100;

    private AutoFitSurfaceView mPreviewView;
    private SurfaceHolder mSurfaceHolder;
    private TextView mStatusText;
    private TextView mStatsText;
    private View mShutterBtn;

    private IAuxCamService mService;
    private final ServiceConnection mServiceConnection = new AuxServiceConnection(this);
    private final Shizuku.OnRequestPermissionResultListener mPermissionListener = new PermResultListener(this);
    private final Shizuku.OnBinderReceivedListener mBinderReceivedListener = new BinderRecvListener(this);
    private final Shizuku.OnBinderDeadListener mBinderDeadListener = new BinderDeadListener(this);

    // ImageReaders for the locked triple-stream configuration
    private ImageReader mColorReader;
    private ImageReader mDepthReader;
    private ImageReader mStillReader;

    private HandlerThread mReaderThread;
    private Handler mReaderHandler;

    private long mTotalColorFrames = 0;
    private long mTotalDepthFrames = 0;
    byte[] mLatestDepth = null;

    long mShutterClickNs = 0;
    private java.util.concurrent.ExecutorService mIoExecutor;

    static {
        try {
            System.loadLibrary("auxcam_engine");
        } catch (Throwable t) {
            Log.w(TAG, "Native auxcam_engine library note: " + t.getMessage());
        }
    }
    public static native void nativeSetThermalThrottled(boolean throttled);
    public native boolean nativeInitEngine(Surface surface, int width, int height);
    public native boolean nativeRenderFrame(
            HardwareBuffer buffer, byte[] depth, int depthW, int depthH, float fStop);
    public native boolean nativeProcessStillBokeh(
            HardwareBuffer buffer, byte[] depth, int depthW, int depthH, float fStop, float targetFarFieldPx, String outPath, long[] timestampsOut);

    boolean mCurrentHighQualityNr = false;
    boolean mCurrentZsl = true;
    private PowerManager mPowerManager;
    private AuxThermalListener mThermalListener;
    private CaptureBroadcastReceiver mCaptureReceiver;

    static class AuxThermalListener implements PowerManager.OnThermalStatusChangedListener {
        private final MainActivity activity;
        AuxThermalListener(MainActivity activity) { this.activity = activity; }
        @Override
        public void onThermalStatusChanged(int status) {
            activity.onThermalChanged(status);
        }
    }

    static class CaptureBroadcastReceiver extends android.content.BroadcastReceiver {
        private final MainActivity activity;
        CaptureBroadcastReceiver(MainActivity activity) { this.activity = activity; }
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean hq = intent.getBooleanExtra("high_quality", false);
            boolean zsl = intent.getBooleanExtra("enable_zsl", true);
            activity.triggerCapture(hq, zsl);
        }
    }

    void onThermalChanged(int status) {
        Log.i(TAG, "Thermal status changed: " + status);
        boolean severe = (status >= PowerManager.THERMAL_STATUS_SEVERE);
        try {
            nativeSetThermalThrottled(severe);
        } catch (UnsatisfiedLinkError e) {
            // ignore if bridge not loaded
        }
    }

    String getThermalAndBatteryInfo() {
        int thermalStatus = -1;
        if (mPowerManager != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            thermalStatus = mPowerManager.getCurrentThermalStatus();
        }
        float batteryTempC = -1.0f;
        try {
            Intent intent = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0);
                batteryTempC = temp / 10.0f;
            }
        } catch (Throwable t) {
            // ignore
        }
        return "thermalStatus=" + thermalStatus + " (" + getThermalStatusName(thermalStatus) + "), batteryTemp=" + batteryTempC + "°C";
    }

    private static String getThermalStatusName(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "NONE";
            case PowerManager.THERMAL_STATUS_LIGHT: return "LIGHT";
            case PowerManager.THERMAL_STATUS_MODERATE: return "MODERATE";
            case PowerManager.THERMAL_STATUS_SEVERE: return "SEVERE";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "CRITICAL";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "EMERGENCY";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "SHUTDOWN";
            default: return "UNKNOWN";
        }
    }

    static class PermResultListener implements Shizuku.OnRequestPermissionResultListener {
        private final MainActivity activity;
        PermResultListener(MainActivity activity) { this.activity = activity; }
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                activity.updateStatus("Shizuku permission granted. Binding service...");
                activity.bindUserService();
            } else {
                activity.updateStatus("Error: Shizuku permission denied.");
            }
        }
    }

    static class BinderRecvListener implements Shizuku.OnBinderReceivedListener {
        private final MainActivity activity;
        BinderRecvListener(MainActivity activity) { this.activity = activity; }
        @Override
        public void onBinderReceived() {
            activity.checkPermissionAndBind();
        }
    }

    static class BinderDeadListener implements Shizuku.OnBinderDeadListener {
        private final MainActivity activity;
        BinderDeadListener(MainActivity activity) { this.activity = activity; }
        @Override
        public void onBinderDead() {
            activity.mService = null;
            activity.updateStatus("Shizuku binder died. Reconnecting...");
        }
    }

    static class AuxServiceConnection implements ServiceConnection {
        private final MainActivity activity;
        AuxServiceConnection(MainActivity activity) { this.activity = activity; }
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            activity.mService = IAuxCamService.Stub.asInterface(binder);
            activity.updateStatus("CameraUserService connected. Opening stereo session...");
            activity.startStereoSession();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            activity.mService = null;
            activity.updateStatus("CameraUserService disconnected.");
        }
    }

    static class AuxCamCallbackImpl extends IAuxCamCallback.Stub {
        private final MainActivity activity;
        AuxCamCallbackImpl(MainActivity a) { this.activity = a; }

        @Override
        public void onSessionReady() {
            activity.runOnUiThread(new SessionReadyRunnable(activity));
        }

        @Override
        public void onSessionError(String msg) {
            activity.runOnUiThread(new ErrorRunnable(activity, msg));
        }

        @Override
        public void onStillCaptured() {
            activity.runOnUiThread(new StillCapturedRunnable(activity));
        }

        @Override
        public void onStats(float colorFps, float depthFps, float dt) {
            activity.runOnUiThread(new StatsRunnable(activity, colorFps, depthFps, dt));
        }
    }

    static class SessionReadyRunnable implements Runnable {
        private final MainActivity activity;
        SessionReadyRunnable(MainActivity a) { activity = a; }
        @Override
        public void run() {
            activity.updateStatus("Stereo Camera 5 Active (Color+Depth+Still)");
        }
    }

    static class ErrorRunnable implements Runnable {
        private final MainActivity activity;
        private final String msg;
        ErrorRunnable(MainActivity a, String m) { activity = a; msg = m; }
        @Override
        public void run() {
            activity.updateStatus("Session Error: " + msg);
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        }
    }

    static class StillCapturedRunnable implements Runnable {
        private final MainActivity activity;
        StillCapturedRunnable(MainActivity a) { activity = a; }
        @Override
        public void run() {
            Toast.makeText(activity, "Still captured with zero preview freeze!", Toast.LENGTH_SHORT).show();
        }
    }

    static class StatsRunnable implements Runnable {
        private final MainActivity activity;
        private final float colorFps;
        private final float depthFps;
        private final float dt;
        StatsRunnable(MainActivity a, float c, float d, float t) {
            activity = a; colorFps = c; depthFps = d; dt = t;
        }
        @Override
        public void run() {
            if (activity.mStatsText != null) {
                activity.mStatsText.setText(String.format(Locale.US, "Color: %.1f fps | Depth: %.1f fps | Δt: %.1f ms", colorFps, depthFps, dt));
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Guard screen on during preview (prevents APU throttling to 113ms)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mIoExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        setContentView(R.layout.activity_main);

        mPreviewView = findViewById(R.id.surface_view);
        mStatusText = findViewById(R.id.tv_status);
        mStatsText = findViewById(R.id.tv_title);
        mShutterBtn = findViewById(R.id.btn_shutter);

        if (mShutterBtn != null) {
            mShutterBtn.setOnClickListener(this);
        }

        if (mPreviewView != null) {
            mPreviewView.setAspectRatio(1440, 1080);
            mPreviewView.getHolder().addCallback(this);
        }

        startReaderThread();
        setupImageReaders();

        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (mPowerManager != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            mThermalListener = new AuxThermalListener(this);
            mPowerManager.addThermalStatusListener(mThermalListener);
        }

        mCaptureReceiver = new CaptureBroadcastReceiver(this);
        android.content.IntentFilter captureFilter = new android.content.IntentFilter("com.zenith.auxcam.CAPTURE_STILL");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mCaptureReceiver, captureFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mCaptureReceiver, captureFilter);
        }

        Shizuku.addRequestPermissionResultListener(mPermissionListener);
        Shizuku.addBinderReceivedListener(mBinderReceivedListener);
        Shizuku.addBinderDeadListener(mBinderDeadListener);

        if (Shizuku.pingBinder()) {
            checkPermissionAndBind();
        } else {
            updateStatus("Waiting for Shizuku service...");
        }
    }

    private void startReaderThread() {
        mReaderThread = new HandlerThread("ImageReaderThread");
        mReaderThread.start();
        mReaderHandler = new Handler(mReaderThread.getLooper());
    }

    private void stopReaderThread() {
        if (mReaderThread != null) {
            mReaderThread.quitSafely();
            try {
                mReaderThread.join();
                mReaderThread = null;
                mReaderHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping reader thread", e);
            }
        }
    }

    private void setupImageReaders() {
        // Locked Ground Truth:
        // Color: 1440x1080 YUV_420_888 USAGE_GPU_SAMPLED_IMAGE
        mColorReader = ImageReader.newInstance(
                1440, 1080, ImageFormat.YUV_420_888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        );
        mColorReader.setOnImageAvailableListener(new ColorImageListener(this), mReaderHandler);

        // Depth: 448x288 Y8 USAGE_GPU_SAMPLED_IMAGE | USAGE_CPU_READ_OFTEN
        mDepthReader = ImageReader.newInstance(
                448, 288, 0x20203859, 3, // ImageFormat.Y8
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_CPU_READ_OFTEN
        );
        mDepthReader.setOnImageAvailableListener(new DepthImageListener(this), mReaderHandler);

        // Still: 4080x3072 YUV_420_888 USAGE_GPU_SAMPLED_IMAGE
        mStillReader = ImageReader.newInstance(
                4080, 3072, ImageFormat.YUV_420_888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        );
        mStillReader.setOnImageAvailableListener(new StillImageListener(this), mReaderHandler);
    }

    static class ColorImageListener implements ImageReader.OnImageAvailableListener {
        private final MainActivity activity;
        ColorImageListener(MainActivity a) { activity = a; }
        @Override
        public void onImageAvailable(ImageReader reader) {
            long t0 = SystemClock.elapsedRealtimeNanos();
            Image img = reader.acquireLatestImage();
            if (img != null) {
                activity.mTotalColorFrames++;
                HardwareBuffer hb = img.getHardwareBuffer();
                if (hb != null) {
                    try {
                        activity.nativeRenderFrame(hb, activity.mLatestDepth, 448, 288, 1.8f);
                    } catch (Throwable t) {
                        Log.e(TAG, "renderFrame error: " + t.getMessage());
                    } finally {
                        hb.close();
                    }
                }
                img.close();
                long t1 = SystemClock.elapsedRealtimeNanos();
                long dtMs = (t1 - t0) / 1_000_000;
                activity.recordPreviewFrameTime(dtMs);
            }
        }
    }

    static class DepthImageListener implements ImageReader.OnImageAvailableListener {
        private final MainActivity activity;
        DepthImageListener(MainActivity a) { activity = a; }
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                activity.mTotalDepthFrames++;
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                if (activity.mLatestDepth == null || activity.mLatestDepth.length != buf.remaining()) {
                    activity.mLatestDepth = new byte[buf.remaining()];
                }
                buf.get(activity.mLatestDepth);
                img.close();
            }
        }
    }

    static class StillImageListener implements ImageReader.OnImageAvailableListener {
        private final MainActivity activity;
        StillImageListener(MainActivity a) { activity = a; }
        @Override
        public void onImageAvailable(ImageReader reader) {
            long t2 = SystemClock.elapsedRealtimeNanos();
            Image img = reader.acquireLatestImage();
            if (img != null) {
                long t3 = SystemClock.elapsedRealtimeNanos();
                HardwareBuffer hb = img.getHardwareBuffer();
                long gpuElapsedNs = 0;
                long t4 = t3;
                long t5 = t3;
                String outPath = null;
                if (hb != null) {
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    File cameraDir = activity.getExternalFilesDir("bokeh_raw");
                    if (cameraDir == null) cameraDir = activity.getFilesDir();
                    if (!cameraDir.exists()) cameraDir.mkdirs();
                    File outFile = new File(cameraDir, "AUXCAM_" + timeStamp + ".rgba");
                    outPath = outFile.getAbsolutePath();

                    // Dump raw Y-plane for Laplacian comparison
                    try {
                        Image.Plane yPlane = img.getPlanes()[0];
                        ByteBuffer yBuf = yPlane.getBuffer();
                        byte[] yBytes = new byte[yBuf.remaining()];
                        yBuf.get(yBytes);
                        File rawYFile = new File(cameraDir, "AUXCAM_" + timeStamp + "_raw_y.bin");
                        FileOutputStream yfos = new FileOutputStream(rawYFile);
                        yfos.write(yBytes);
                        yfos.close();
                    } catch (Throwable t) {
                        Log.w(TAG, "Raw Y save note: " + t.getMessage());
                    }

                    long[] timestampsOut = new long[3];
                    boolean ok = activity.nativeProcessStillBokeh(hb, activity.mLatestDepth, 448, 288, 1.8f, 15.0f, outPath, timestampsOut);
                    t4 = SystemClock.elapsedRealtimeNanos();
                    gpuElapsedNs = timestampsOut[0];
                    t5 = t4;
                    hb.close();
                }
                img.close();
                activity.dispatchStillComplete(activity.mShutterClickNs, t2, t3, t4, t5, gpuElapsedNs, outPath, activity.mCurrentHighQualityNr, activity.mCurrentZsl);
            }
        }
    }

    void dispatchStillComplete(long t0, long t2, long t3, long t4, long t5, long gpuElapsedNs, String outPath, boolean highQualityNr, boolean zslEnabled) {
        long d_shutter_to_yuv = (t2 - t0) / 1_000_000;
        long d_gpu_bokeh = (t4 - t3) / 1_000_000;
        long total_shutter_to_buffer = (t4 - t0) / 1_000_000;
        long total_shutter_to_file = (t5 - t0) / 1_000_000;
        long gpu_internal_ms = gpuElapsedNs / 1_000_000;
        long fileSizeBytes = 0;
        if (outPath != null) {
            File f = new File(outPath);
            if (f.exists()) fileSizeBytes = f.length();
        }

        Log.i(TAG, "================ STILL PATH END-TO-END VERIFICATION ================");
        Log.i(TAG, "Environment: " + getThermalAndBatteryInfo());
        Log.i(TAG, "Mode: " + (highQualityNr ? "HIGH_QUALITY_NR" : "FAST_NR") + " | ZSL: " + (zslEnabled ? "ENABLED" : "DISABLED"));
        Log.i(TAG, "Format: YUV_420_888 (4080x3072, 12.5 MP) -> Vulkan Gather (1020x768, 32-tap) -> 4K Composite (4080x3072)");
        Log.i(TAG, "t0 (shutter_command):           " + t0 + " ns");
        Log.i(TAG, "t2 (HAL YUV buffer available):   " + t2 + " ns");
        Log.i(TAG, "t3 (GPU bokeh pass start):       " + t3 + " ns");
        Log.i(TAG, "t4 (COMPOSITE BUFFER COMPLETE):  " + t4 + " ns");
        Log.i(TAG, "t5 (FILE CLOSED / SAVED):        " + t5 + " ns");
        Log.i(TAG, "--------------------------------------------------------------------");
        Log.i(TAG, "Delta 1+2 (Shutter -> HAL YUV Buffer): " + d_shutter_to_yuv + " ms");
        Log.i(TAG, "Delta GPU (32-tap Gather + Composite):  " + d_gpu_bokeh + " ms (GPU submit-to-fence: " + gpu_internal_ms + " ms)");
        Log.i(TAG, "TOTAL SHUTTER -> COMPOSITE COMPLETE:    " + total_shutter_to_buffer + " ms (Gate: <= 500 ms) -> " + (total_shutter_to_buffer <= 500 ? "PASS" : "FAIL"));
        Log.i(TAG, "TOTAL SHUTTER -> FILE CLOSED (Raw RGBA Write): " + total_shutter_to_file + " ms | size=" + fileSizeBytes + " bytes (" + String.format(Locale.US, "%.2f MB", fileSizeBytes / (1024.0f * 1024.0f)) + ") [Note: Real JPEG encode adds ~150-200ms]");
        Log.i(TAG, "Saved File: " + outPath);
        Log.i(TAG, "====================================================================");
    }

    static class StillSaveRunnable implements Runnable {
        private final MainActivity activity;
        private final byte[] bytes;
        private final byte[] depthBytes;
        private final long t0, t2, t3, t4;
        private final boolean highQualityNr;

        StillSaveRunnable(MainActivity activity, byte[] bytes, byte[] depthBytes, long t0, long t2, long t3, long t4, boolean highQualityNr) {
            this.activity = activity;
            this.bytes = bytes;
            this.depthBytes = (depthBytes != null) ? depthBytes.clone() : null;
            this.t0 = t0;
            this.t2 = t2;
            this.t3 = t3;
            this.t4 = t4;
            this.highQualityNr = highQualityNr;
        }

        @Override
        public void run() {
            try {
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                File cameraDir = new File(outDir, "Camera");
                if (!cameraDir.exists()) cameraDir.mkdirs();
                File outFile = new File(cameraDir, "AUXCAM_" + timeStamp + ".jpg");

                FileOutputStream fos = new FileOutputStream(outFile);
                fos.write(bytes);
                fos.close();

                if (depthBytes != null) {
                    File depthFile = new File(cameraDir, "AUXCAM_" + timeStamp + "_depth.y8");
                    FileOutputStream dfos = new FileOutputStream(depthFile);
                    dfos.write(depthBytes);
                    dfos.close();
                }

                long t5 = SystemClock.elapsedRealtimeNanos();

                long d_shutter_to_buf = (t2 - t0) / 1_000_000;
                long d_memcpy = (t4 - t3) / 1_000_000;
                long d_file_io = (t5 - t4) / 1_000_000;
                long totalLatencyMs = (t5 - t0) / 1_000_000;

                Log.i(TAG, "================ STILL PATH TIMESTAMPS (" + (highQualityNr ? "HIGH_QUALITY_NR" : "FAST_NR") + ") ================");
                Log.i(TAG, "Environment: " + activity.getThermalAndBatteryInfo());
                Log.i(TAG, "t0 (shutter_command):   " + t0 + " ns");
                Log.i(TAG, "t2 (last_buffer/avail): " + t2 + " ns");
                Log.i(TAG, "t3 (memcpy_start):      " + t3 + " ns");
                Log.i(TAG, "t4 (memcpy_end):        " + t4 + " ns");
                Log.i(TAG, "t5 (file_close):        " + t5 + " ns");
                Log.i(TAG, "------------------------------------------------------");
                Log.i(TAG, "Delta 1+2 (shutter->HAL buffer): " + d_shutter_to_buf + " ms | size=" + bytes.length + " bytes (" + String.format(Locale.US, "%.2f MB", bytes.length / (1024.0f * 1024.0f)) + ")");
                Log.i(TAG, "Delta 3   (memcpy extraction):   " + d_memcpy + " ms | size=" + bytes.length + " bytes");
                Log.i(TAG, "Delta 4   (file_io/storage):     " + d_file_io + " ms | size=" + bytes.length + " bytes");
                Log.i(TAG, "TOTAL STILL LATENCY (t0->t5):     " + totalLatencyMs + " ms | file=" + outFile.getName() + " (" + bytes.length + " bytes)");
                Log.i(TAG, "======================================================");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to write still image", t);
            }
        }
    }

    void dispatchStillSave(byte[] bytes, byte[] depthBytes, long t0, long t2, long t3, long t4, boolean highQualityNr) {
        if (mIoExecutor != null && !mIoExecutor.isShutdown()) {
            mIoExecutor.execute(new StillSaveRunnable(this, bytes, depthBytes, t0, t2, t3, t4, highQualityNr));
        }
    }

    void startStereoSession() {
        if (mService == null || mDepthReader == null || mStillReader == null || mColorReader == null) {
            return;
        }

        // Camera stream 0 feeds ImageReader (mColorReader, YUV AHB)
        // Vulkan imports AHB, executes 32-tap bokeh, and presents to SurfaceView
        Surface previewSurface = mColorReader.getSurface();
        if (previewSurface == null) {
            return;
        }

        try {
            mService.setCallback(new AuxCamCallbackImpl(this));
            boolean ok = mService.openStereoSession(
                    previewSurface, 1440, 1080,
                    mDepthReader.getSurface(), 448, 288,
                    mStillReader.getSurface(), 4080, 3072,
                    "0"
            );
            if (!ok) {
                updateStatus("Failed to open stereo session");
            } else {
                updateStatus("Live Vulkan Bokeh Engine Active (Stream 0 -> YUV AHB -> Vulkan -> SurfaceView)");
            }
        } catch (RemoteException e) {
            updateStatus("Remote exception: " + e.getMessage());
        }
    }

    void checkPermissionAndBind() {
        if (Shizuku.getVersion() < 11) {
            updateStatus("Error: Shizuku V11+ required.");
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindUserService();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        }
    }

    void bindUserService() {
        updateStatus("Binding Privileged Camera UserService under Shell...");
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(), CameraUserService.class.getName())
        )
                .daemon(false)
                .version(1)
                .processNameSuffix("auxcam_privileged");

        Shizuku.bindUserService(args, mServiceConnection);
    }

    void updateStatus(String status) {
        Log.i(TAG, "Status: " + status);
        if (mStatusText != null) {
            mStatusText.setText(status);
        }
    }

    void triggerCapture(boolean highQualityNr) {
        triggerCapture(highQualityNr, true);
    }

    void triggerCapture(boolean highQualityNr, boolean enableZsl) {
        if (mService != null) {
            mCurrentHighQualityNr = highQualityNr;
            mCurrentZsl = enableZsl;
            mShutterClickNs = SystemClock.elapsedRealtimeNanos();
            updateStatus("Capturing still (" + (highQualityNr ? "HIGH_QUALITY_NR" : "FAST_NR") + ", ZSL=" + enableZsl + ")...");
            try {
                mService.triggerStillWithZsl(mShutterClickNs, highQualityNr, enableZsl);
            } catch (RemoteException e) {
                Log.e(TAG, "triggerStill remote exception", e);
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mShutterBtn) {
            triggerCapture(mCurrentHighQualityNr);
        }
    }

    private final java.util.List<Long> mPreviewFrameTimes = new java.util.ArrayList<>();
    void recordPreviewFrameTime(long dtMs) {
        synchronized (mPreviewFrameTimes) {
            mPreviewFrameTimes.add(dtMs);
            if (mPreviewFrameTimes.size() % 60 == 0) {
                java.util.List<Long> sorted = new java.util.ArrayList<>(mPreviewFrameTimes);
                java.util.Collections.sort(sorted);
                int n = sorted.size();
                long p50 = sorted.get(n / 2);
                long p95 = sorted.get((int) (n * 0.95));
                long min = sorted.get(0);
                long max = sorted.get(n - 1);
                int b_lt25 = 0, b_25_35 = 0, b_35_40 = 0, b_40_50 = 0, b_gt50 = 0;
                for (long t : sorted) {
                    if (t < 25) b_lt25++;
                    else if (t <= 35) b_25_35++;
                    else if (t <= 40) b_35_40++;
                    else if (t <= 50) b_40_50++;
                    else b_gt50++;
                }
                float pct25_35 = (100.0f * b_25_35) / n;
                Log.i(TAG, "=== LIVE VULKAN BOKEH PREVIEW FRAME-TIME HISTOGRAM (N=" + n + ") ===");
                Log.i(TAG, "p50=" + p50 + " ms | p95=" + p95 + " ms | min=" + min + " ms | max=" + max + " ms");
                Log.i(TAG, "[<25ms]=" + b_lt25 + " | [25-35ms]=" + b_25_35 + " (" + String.format(Locale.US, "%.1f", pct25_35) + "%) | [35-40ms]=" + b_35_40 + " | [40-50ms]=" + b_40_50 + " | [>50ms]=" + b_gt50);
                Log.i(TAG, "======================================================================");
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceHolder = holder;
        Log.i(TAG, "surfaceCreated: holder=" + holder + ", surface=" + holder.getSurface());
        try {
            boolean ok = nativeInitEngine(holder.getSurface(), 1440, 1080);
            Log.i(TAG, "nativeInitEngine result: " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "nativeInitEngine exception: " + t.getMessage());
        }
        if (mService != null) {
            startStereoSession();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mSurfaceHolder = holder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurfaceHolder = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopReaderThread();
        if (mCaptureReceiver != null) {
            try {
                unregisterReceiver(mCaptureReceiver);
            } catch (Throwable t) {
                // ignore
            }
            mCaptureReceiver = null;
        }
        if (mPowerManager != null && mThermalListener != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                mPowerManager.removeThermalStatusListener(mThermalListener);
            } catch (Throwable t) {
                // ignore
            }
            mThermalListener = null;
        }
        if (mIoExecutor != null) {
            mIoExecutor.shutdown();
        }
        if (mColorReader != null) mColorReader.close();
        if (mDepthReader != null) mDepthReader.close();
        if (mStillReader != null) mStillReader.close();

        if (mService != null) {
            try {
                mService.closeSession();
            } catch (RemoteException e) {
                // ignore
            }
        }
        Shizuku.removeRequestPermissionResultListener(mPermissionListener);
        Shizuku.removeBinderReceivedListener(mBinderReceivedListener);
        Shizuku.removeBinderDeadListener(mBinderDeadListener);
    }
}
