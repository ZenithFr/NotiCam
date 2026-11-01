package com.zenith.auxcam

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraManager
import android.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import rikka.shizuku.Shizuku
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.roundToInt

/** Native JNI entry points, implemented by [MainActivity]. */
interface NativeBridge {
    fun initEngine(surface: Surface, width: Int, height: Int): Boolean
    fun renderFrame(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        alphaMatte: ByteArray?,
        alphaW: Int,
        alphaH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
    ): Boolean

    fun setCameraOpticalParams(focalMm: Float, sensorWidthMm: Float, baseFStop: Float)

    fun processStill(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        alphaMatte: ByteArray?,
        alphaW: Int,
        alphaH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
        outPath: String,
        timestampsOut: LongArray,
    ): Boolean

    fun setThermalThrottled(throttled: Boolean)
    fun setDisableGuided(disable: Boolean) {}
    fun destroyEngine()
    fun acquireHardwareBuffer(buffer: HardwareBuffer) {}
    fun releaseHardwareBuffer(buffer: HardwareBuffer) {}
    fun notifyMatteDrop() {}
    fun getAhbRefcount(): Int = 0
}

/** Everything the Compose chrome can ask the camera to do. */
interface CameraActions {
    fun onFlashClick()
    fun onBokehToggle()
    fun onSettingsToggle()
    fun onModeSelected(mode: CaptureMode)
    fun onZoomSelected(zoom: Float)
    fun onZoomGesture(scaleFactor: Float)

    /** A drag has started on the chip row — the same gesture family as a pinch. */
    fun onZoomDragStart()

    /** Horizontal drag delta in pixels while scrubbing the chip row. */
    fun onZoomDrag(deltaPx: Float)

    /** The chip-row drag ended or was cancelled. */
    fun onZoomDragEnd()

    /** @param fStop real f-number, e.g. 2.36f — continuous, not an index. */
    fun onApertureChanged(fStop: Float)

    /** @param ev exposure compensation in EV, continuous (sent as 1/3 steps). */
    fun onEvChanged(ev: Float)
    fun onGridToggle()
    fun onRawToggle()
    fun onTimerCycle()

    /** 12MP <-> 50MP. */
    fun onResolutionToggle()

    /** Tele lens assist above 2x. */
    fun onTelephotoToggle()

    /** Cycle 1:1 -> 4:3 -> 16:9 -> Full. */
    fun onFrameRatioCycle()

    /** Pick a framing directly (the segmented control). */
    fun onFrameRatioSelected(ratio: FrameRatio)

    /** Long-press on the viewfinder: lock AE/AF at the current point. */
    fun onFocusLock()

    /** Tap while locked: release. */
    fun onFocusUnlock()

    /** Manual focus fraction changed (0.0 = inf, 1.0 = macro). */
    fun onManualFocusChanged(fraction: Float)

    /** Reset manual focus / AE/AF lock back to auto. */
    fun onFocusReset()
    fun onShutterClick()
    /** Opens the phone's gallery on the newest picture. */
    fun onGalleryOpen()

    /** Normalised tap inside the viewfinder (0..1, upright screen space). */
    fun onFocusAt(normalisedX: Float, normalisedY: Float)

    fun onPreviewSurfaceCreated(holder: SurfaceHolder)
    fun onPreviewSurfaceChanged(holder: SurfaceHolder)
    fun onPreviewSurfaceDestroyed(holder: SurfaceHolder)
}

/**
 * Owns the camera engine, the Shizuku binding and the single source of truth for
 * the Compose UI. Deliberately framework-light (no ViewModel, no LiveData): it
 * exposes one immutable [CameraUiState] snapshot through Compose `State`, so the
 * chrome recomposes only where a value actually changed.
 */
class CameraController(
    private val activity: Activity,
    private val native: NativeBridge,
) : CameraActions, SurfaceHolder.Callback, ServiceConnection {

    companion object {
        private const val TAG = "NotiCam/Controller"
        private const val SHIZUKU_REQUEST_CODE = 100
        private const val Y8_FORMAT = 0x20203859
        private const val THUMB_W = 168
        private const val THUMB_H = 126
        const val CAPTURE_JPEG_QUALITY = 100
        @Volatile var forceJpeg98 = false
        const val DEBUG_DUMP_RGBA = false

        /** Above this zoom the telephoto assist hands over to the physical tele. */
        private const val TELE_THRESHOLD = 2.0f

        /** Apple's Portrait aperture ladder: f/1.4 → f/16. */
        val FSTOPS = floatArrayOf(
            1.4f, 1.6f, 1.8f, 2.0f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4.0f, 4.5f,
            5.0f, 5.6f, 6.3f, 7.1f, 8.0f, 9.0f, 10f, 11f, 13f, 14f, 16f,
        )
    }

    // ── UI state ────────────────────────────────────────────────────────────
    private val _uiState = mutableStateOf(
        CameraUiState(
            status = "Connecting…",
            telemetry = "VIEWFINDER · idle",
            lensCount = 1,
        ),
    )
    val uiState: State<CameraUiState> get() = _uiState

    private val state: CameraUiState get() = _uiState.value
    private fun update(block: (CameraUiState) -> CameraUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _uiState.value = block(_uiState.value)
        } else {
            main.post { if (!disposed) _uiState.value = block(_uiState.value) }
        }
    }

    // ── Camera plumbing ─────────────────────────────────────────────────────
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null

    private var colorReader: ImageReader? = null
    private var depthReader: ImageReader? = null
    private var stillReader: ImageReader? = null

    private var service: IAuxCamService? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var serviceBinding = false
    private var sessionToken = 0
    @Volatile private var surfaceHolder: SurfaceHolder? = null
    @Volatile private var engineReady = false
    @Volatile private var disposed = false
    @Volatile private var foreground = false
    private var timerRunning = false
    @Volatile private var captureState: CameraUiState? = null
    private val captureTimeout = Runnable {
        if (stillCaptureInProgress.getAndSet(false)) {
            captureState = null
            update { it.copy(captureBusy = false, status = "Capture timed out — try again") }
        }
    }
    private val stillCaptureInProgress = AtomicBoolean(false)
    private var matteSegmenter: DualMatteSegmenter? = null
    @Volatile private var latestMatte = ByteArray(256 * 256)
    @Volatile
    private var hasRealMatte = false
    private val matteExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "MatteWorkerThread").apply { priority = Thread.NORM_PRIORITY }
    }
    private val isMatteWorkerBusy = AtomicBoolean(false)
    private var lastMatteDispatchMs = 0L
    private var matteDropCount5s = 0
    private var lastMatteDropLogMs = 0L
    private var stillThread: HandlerThread? = null
    private var stillHandler: Handler? = null
    private var lastShutterTriggerNs = 0L

    @Volatile
    private var latestDepth: ByteArray? = null
    private val depthFrames = java.util.ArrayDeque<FrameData.DepthFrame>()
    @Volatile private var frameGeneration = 0
    @Volatile private var matteTimestampNs = 0L

    private fun depthFor(timestamp: Long): ByteArray? = synchronized(depthFrames) {
        if (currentLens == "0") FrameData.closestDepth(depthFrames, timestamp) else null
    }

    private fun matteFor(timestamp: Long): ByteArray? =
        if (hasRealMatte && kotlin.math.abs(timestamp - matteTimestampNs) <= FrameData.MATTE_MAX_AGE_NS) latestMatte else null

    private var shutterClickNs = 0L
    private var shutterDispatchNs = 0L
    private var lastPreviewTimestampNs = 0L
    private var maxPreviewGapNsDuringCapture = 0L
    private var capturePreviewFrames = 0
    private var latestPhotoUri: Uri? = null
    private var lensSwitching = false

    private var lastCpuFrameLogMs = 0L
    private var lastColorCbNs = 0L
    private var buffersAcquiredCount = 0L
    private var buffersReleasedCount = 0L

    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var captureReceiver: BroadcastReceiver? = null

    private var lastSentZoom = 1f
    private var lastSentEvSteps = 0
    private val physicalLenses = mutableListOf<String>()
    @Volatile private var currentLens = "0"
    private var onTele = false
    private var viewfinderGeometry = ViewfinderGeometry.defaultPortrait()

    private var started = false

    // ── Shizuku callbacks ───────────────────────────────────────────────────
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            update { it.copy(status = "Connecting…") }
            bindUserService()
        } else {
            update { it.copy(status = "Shizuku permission denied") }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkPermissionAndBind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        serviceBinding = false
        service = null
        update { it.copy(status = "Reconnecting…", sessionReady = false) }
    }

    // ═══════════════════════════ LIFECYCLE ══════════════════════════════════

    fun start() {
        foreground = true
        if (started) {
            if (!state.sessionReady) startStereoSession()
            return
        }
        started = true

        discoverLenses()
        startReaderThread()
        setupImageReaders()

        powerManager = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                onThermalChanged(status)
            }
            thermalListener = listener
            powerManager?.addThermalStatusListener(listener)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val hq = intent.getBooleanExtra("high_quality", false)
                val zsl = intent.getBooleanExtra("enable_zsl", true)
                forceJpeg98 = intent.getBooleanExtra("quality_98", false)
                // Legacy diagnostics never select a removed capture mode.
                triggerCapture(hq, zsl)
            }
        }
        captureReceiver = receiver
        val filter = IntentFilter("com.zenith.auxcam.CAPTURE_STILL")
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                activity.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "capture receiver note: ${t.message}")
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (Shizuku.pingBinder()) {
            checkPermissionAndBind()
        } else {
            update { it.copy(status = "Waiting for Shizuku…") }
        }
    }

    fun pause() {
        sessionToken++
        foreground = false
        timerRunning = false
        main.removeCallbacks(timerTick)
        try { service?.closeSession() } catch (e: RemoteException) { Log.w(TAG, "pause session", e) }
        update { it.copy(sessionReady = false, captureBusy = stillCaptureInProgress.get(), status = "Camera paused") }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        foreground = false
        engineReady = false
        main.removeCallbacksAndMessages(null)

        captureReceiver?.let {
            try {
                activity.unregisterReceiver(it)
            } catch (t: Throwable) {
                Log.w(TAG, "receiver already gone")
            }
        }
        captureReceiver = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let {
                try {
                    powerManager?.removeThermalStatusListener(it)
                } catch (t: Throwable) {
                    Log.w(TAG, "thermal listener already gone")
                }
            }
        }
        thermalListener = null

        try { service?.closeSession() } catch (e: RemoteException) { Log.w(TAG, "closeSession failed", e) }
        service = null
        colorReader?.setOnImageAvailableListener(null, null)
        depthReader?.setOnImageAvailableListener(null, null)
        stillReader?.setOnImageAvailableListener(null, null)
        stopReaderThread()
        stillThread?.quitSafely()
        try { stillThread?.join() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        stillThread = null
        stillHandler = null
        colorReader?.close()
        depthReader?.close()
        stillReader?.close()
        colorReader = null
        depthReader = null
        stillReader = null
        matteExecutor.shutdown()
        // Inference owns its interpreter until the worker has finished.
        try {
            if (matteExecutor.awaitTermination(5, TimeUnit.SECONDS)) matteSegmenter?.close()
        } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        io.shutdown() // let an already queued save finish

        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)

        serviceArgs?.let {
            try { Shizuku.unbindUserService(it, this, true) }
            catch (e: Exception) { Log.w(TAG, "Service unbind failed", e) }
        }
        serviceArgs = null
        native.destroyEngine()
        started = false
    }

    private fun startReaderThread() {
        val thread = HandlerThread("ImageReaderThread").also { it.start() }
        readerThread = thread
        readerHandler = Handler(thread.looper)
    }

    private fun stopReaderThread() {
        readerThread?.quitSafely()
        try {
            readerThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "reader thread join interrupted", e)
        }
        readerThread = null
        readerHandler = null
    }

    // ═══════════════════════════ SURFACE ═════════════════════════════════════

    override fun onPreviewSurfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        // Size the swapchain from the *view*, not the sensor: the composable has
        // already measured the SurfaceView to exactly 4:3, so the initial extent is
        // correct and no swapchain recreation is needed on the first frame.
        val frame = holder.surfaceFrame
        val width = frame.width().takeIf { it > 0 } ?: DeviceProfile.COLOR_WIDTH
        val height = frame.height().takeIf { it > 0 } ?: DeviceProfile.COLOR_HEIGHT
        viewfinderGeometry = ViewfinderGeometry(width, height, isPortrait = true)
        viewfinderGeometry.assertMatchesSurface(width, height)
        Log.i(TAG, "ViewfinderGeometry derived: surface=${width}x${height}, working=${viewfinderGeometry.workingWidth}x${viewfinderGeometry.workingHeight}, cocRef=${viewfinderGeometry.cocReferenceWidth}")
        val ok = native.initEngine(holder.surface, width, height)
        engineReady = ok
        Log.i(TAG, "nativeInitEngine -> $ok")
        if (!ok) update { it.copy(status = "Camera engine unavailable", sessionReady = false) }
        if (ok && service != null) startStereoSession()
    }

    override fun onPreviewSurfaceChanged(holder: SurfaceHolder) {
        surfaceHolder = holder
        val frame = holder.surfaceFrame
        val width = frame.width()
        val height = frame.height()
        if (width > 0 && height > 0) {
            viewfinderGeometry = ViewfinderGeometry(width, height, isPortrait = true)
            viewfinderGeometry.assertMatchesSurface(width, height)
            Log.i(TAG, "onPreviewSurfaceChanged: geometry updated to ${width}x${height}")
        }
    }

    override fun onPreviewSurfaceDestroyed(holder: SurfaceHolder) {
        engineReady = false
        surfaceHolder = null
    }

    // SurfaceHolder.Callback (kept for the AndroidView bridge)
    override fun surfaceCreated(holder: SurfaceHolder) = onPreviewSurfaceCreated(holder)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        onPreviewSurfaceChanged(holder)

    override fun surfaceDestroyed(holder: SurfaceHolder) = onPreviewSurfaceDestroyed(holder)

    // ═══════════════════════════ SHIZUKU ═════════════════════════════════════

    private fun checkPermissionAndBind() {
        if (Shizuku.getVersion() < 11) {
            update { it.copy(status = "Shizuku v11+ required") }
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindUserService()
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    }

    private fun bindUserService() {
        if (disposed || serviceBinding || service != null) return
        serviceBinding = true
        update { it.copy(status = "Binding service…") }
        val args = Shizuku.UserServiceArgs(
            ComponentName(activity.packageName, CameraUserService::class.java.name),
        )
            .daemon(false)
            .version(4)
            .processNameSuffix("auxcam_privileged")
        serviceArgs = args
        try { Shizuku.bindUserService(args, this) }
        catch (e: Exception) {
            serviceBinding = false
            update { it.copy(status = "Could not bind camera service") }
            Log.e(TAG, "Service binding failed", e)
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        serviceBinding = false
        if (disposed) return
        service = binder?.let { IAuxCamService.Stub.asInterface(it) }
        update { it.copy(status = "Opening session…") }
        startStereoSession()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        serviceBinding = false
        service = null
        update { it.copy(status = "Disconnected", sessionReady = false) }
    }

    // ═══════════════════════════ SESSION ═════════════════════════════════════

    private fun setupImageReaders() {
        val handler = readerHandler ?: return

        if (matteSegmenter == null) {
            matteSegmenter = DualMatteSegmenter(activity).also {
                it.initialize()
            }
        }

        if (stillThread == null) {
            val sThread = HandlerThread("StillReaderThread").also { it.start() }
            stillThread = sThread
            stillHandler = Handler(sThread.looper)
        }

        colorReader = ImageReader.newInstance(
            DeviceProfile.COLOR_WIDTH,
            DeviceProfile.COLOR_HEIGHT,
            ImageFormat.YUV_420_888,
            3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN,
        ).also { reader ->
            reader.setOnImageAvailableListener({ r -> onColorImage(r) }, handler)
        }

        depthReader = ImageReader.newInstance(
            DeviceProfile.DEPTH_WIDTH,
            DeviceProfile.DEPTH_HEIGHT,
            Y8_FORMAT,
            3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN,
        ).also { reader ->
            reader.setOnImageAvailableListener({ r -> onDepthImage(r) }, handler)
        }

        stillReader = createStillReader()
    }

    private fun startStereoSession() {
        if (!foreground || disposed || !engineReady) return
        val svc = service ?: return
        val holder = surfaceHolder ?: return
        val color = colorReader ?: return
        val depth = depthReader ?: return
        val still = stillReader ?: return
        val stillW = still.width
        val stillH = still.height

        // The preview surface must exist before the engine is initialised.
        if (!holder.surface.isValid) return

        try {
            val token = ++sessionToken
            svc.setCallback(object : IAuxCamCallback.Stub() {
                override fun onSessionReady(cameraId: String?) { post { if (token == sessionToken) this@CameraController.onSessionReady(cameraId) } }
                override fun onSessionError(msg: String?) { post { if (token == sessionToken) this@CameraController.onSessionError(msg) } }
                override fun onStillCaptured() { post { if (token == sessionToken) this@CameraController.onStillCaptured() } }
                override fun onStats(colorFps: Float, depthFps: Float, dt: Float) {
                    post { if (token == sessionToken) this@CameraController.onStats(colorFps, depthFps, dt) }
                }
            })

            val ok = svc.openStereoSession(
                color.surface, DeviceProfile.COLOR_WIDTH, DeviceProfile.COLOR_HEIGHT,
                depth.surface, DeviceProfile.DEPTH_WIDTH, DeviceProfile.DEPTH_HEIGHT,
                still.surface, stillW, stillH, still.imageFormat,
                currentLens,
            )
            update { it.copy(status = if (ok) "Starting engine…" else "Retrying session…") }
            if (!ok) {
                main.postDelayed({
                    if (service != null && surfaceHolder?.surface?.isValid == true) {
                        startStereoSession()
                    }
                }, 800L)
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "openStereoSession failed", e)
            update { it.copy(status = "Remote error") }
        }
    }

    private fun post(block: () -> Unit) {
        main.post { if (!disposed) block() }
    }

    private fun onSessionReady(cameraId: String?) {
        if (!foreground || disposed) return
        lensSwitching = false
        val actualLens = if (cameraId == DeviceProfile.CAMERA_ID) "0" else cameraId ?: "0"
        if (actualLens != currentLens) {
            currentLens = actualLens
            invalidateAuxiliaryFrames()
        }
        refreshHighResolutionCapability()
        onTele = currentLens == "3"
        if (onTele) native.setCameraOpticalParams(11.12f, 5.80f, 2.40f)
        else native.setCameraOpticalParams(5.56f, 8.16f, 1.88f)
        val actualZoom = if (currentLens == "2") 0.6f else if (currentLens == "3") state.zoom.coerceAtLeast(2f) else state.zoom.coerceAtLeast(1f)
        update {
            it.copy(
                zoom = actualZoom,
                status = "Ready",
                sessionReady = true,
                lensIndex = physicalLenses.indexOf(currentLens).coerceAtLeast(0),
                lensCount = physicalLenses.size.coerceAtLeast(1),
            )
        }
    }

    private fun refreshHighResolutionCapability() {
        val svc = service ?: return
        val generation = frameGeneration
        io.execute {
            val size = try { svc.getHighResolutionJpegSize() } catch (e: RemoteException) { null }
            post {
                if (generation == frameGeneration) {
                    val width = size?.getOrNull(0) ?: 0
                    val height = size?.getOrNull(1) ?: 0
                    update { it.copy(highResolutionWidth = width, highResolutionHeight = height) }
                }
            }
        }
    }

    private fun onSessionError(msg: String?) {
        if (state.resolution == Resolution.High) {
            Toast.makeText(activity, "50 MP stream unavailable. Returning to 12 MP.", Toast.LENGTH_LONG).show()
            finishCapture("50 MP stream rejected")
            update { it.copy(resolution = Resolution.Standard, bokehEnabled = false, sessionReady = false) }
            rebuildStillSession("50 MP unavailable for this stream combination. Restoring 12 MP…")
            return
        }
        timerRunning = false
        main.removeCallbacks(timerTick)
        finishCapture(msg ?: "Camera error")
        update { it.copy(status = msg ?: "Session error", sessionReady = false) }
        Toast.makeText(activity, msg ?: "Camera error", Toast.LENGTH_LONG).show()
        if (lensSwitching) {
            lensSwitching = false
            currentLens = "0"
            try {
                service?.setPhysicalLens("0")
            } catch (e: RemoteException) {
                Log.w(TAG, "lens revert failed")
            }
        }
    }

    private fun onStillCaptured() {
        // Camera2 completion means a buffer arrived, not that storage succeeded.
    }

    private fun onStats(colorFps: Float, depthFps: Float, dt: Float) {
        matteSegmenter?.onFpsMeasured(colorFps)
        val text = String.format(
            Locale.US,
            "VIEWFINDER · %.0f FPS · DEPTH · %.0f FPS · Δt %.1f ms",
            colorFps,
            depthFps,
            dt,
        )
        update { it.copy(telemetry = text) }
    }

    // ═══════════════════════════ IMAGE STREAMS ═══════════════════════════════

    /**
     * Map f-stop (1.4..16.0) directly to target_far_field_px in [5.0, 30.0].
     * Item 11: Switch CoC constants with lens selection (2.08x factor on Cam 3 telephoto).
     * At f/2.8 (default), returns 15.0 px on main lens, 31.2 px on Cam 3.
     */
    private fun fStopToTargetFarFieldPx(fStop: Float, lensId: String = currentLens): Float {
        val lensMultiplier = if (lensId == "3") 2.08f else 1.0f
        val px = 15.0f * (2.8f / fStop.coerceAtLeast(1.0f)) * lensMultiplier
        return px.coerceIn(5.0f, 30.0f * lensMultiplier)
    }

    private fun onColorImage(reader: ImageReader) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val sensorCbMs = if (lastColorCbNs > 0L) (t0 - lastColorCbNs) / 1_000_000.0 else 0.0
        lastColorCbNs = t0
        val nowPreviewNs = t0
        if (lastPreviewTimestampNs > 0L) {
            val gapNs = nowPreviewNs - lastPreviewTimestampNs
            if (stillCaptureInProgress.get()) {
                capturePreviewFrames++
                if (gapNs > maxPreviewGapNsDuringCapture) {
                    maxPreviewGapNsDuringCapture = gapNs
                }
            }
        }
        lastPreviewTimestampNs = nowPreviewNs

        val tAcq0 = SystemClock.elapsedRealtimeNanos()
        val image = reader.acquireLatestImage() ?: return
        val tAcq1 = SystemClock.elapsedRealtimeNanos()
        buffersAcquiredCount++

        val imageReaderCbMs = (tAcq1 - tAcq0) / 1_000_000.0
        var ahbImportMs = 0.0
        var engineSubmitMs = 0.0

        try {
            if (!foreground || !state.sessionReady || disposed) return
            val seg = matteSegmenter
            val nowMs = SystemClock.elapsedRealtime()
            val cadenceAllow = (nowMs - lastMatteDispatchMs >= 83L) // <= 12 Hz ceiling

            if (state.bokehActive && seg != null && seg.isAvailable && cadenceAllow) {
                if (isMatteWorkerBusy.get()) {
                    matteDropCount5s++
                    native.notifyMatteDrop()
                    if (nowMs - lastMatteDropLogMs >= 5000L) {
                        Log.i(TAG, "MATTE_DROP: drops=$matteDropCount5s in 5s")
                        matteDropCount5s = 0
                        lastMatteDropLogMs = nowMs
                    }
                } else if (isMatteWorkerBusy.compareAndSet(false, true)) {
                    lastMatteDispatchMs = nowMs
                    val generation = frameGeneration
                    val sensorTimestamp = image.timestamp
                    // preprocessFrame copies into model-owned tensors before
                    // returning; the inference worker never needs a camera AHB.
                    try {
                        if (seg.preprocessFrame(image)) {
                            matteExecutor.execute {
                                try {
                                    if (seg.runInference()) {
                                        val matte = ByteArray(256 * 256)
                                        seg.getLatestAlphaMatte(matte)
                                        if (generation == frameGeneration && !disposed) {
                                            latestMatte = matte
                                            matteTimestampNs = sensorTimestamp
                                            hasRealMatte = true
                                        }
                                    }
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Worker inference error: ${t.message}")
                                } finally {
                                    isMatteWorkerBusy.set(false)
                                }
                            }
                        } else isMatteWorkerBusy.set(false)
                    } catch (t: Throwable) {
                        isMatteWorkerBusy.set(false)
                        Log.w(TAG, "Matte preprocessing failed", t)
                    }
                }
            }

            val triggerL8 = File(activity.getExternalFilesDir("diag"), "trigger_l8")
            if (triggerL8.exists()) {
                triggerL8.delete()
                dumpPreviewDiagnostics(image)
            }

            val tAhb0 = SystemClock.elapsedRealtimeNanos()
            val buffer = try {
                image.hardwareBuffer
            } catch (t: Throwable) {
                null
            }
            val tAhb1 = SystemClock.elapsedRealtimeNanos()
            ahbImportMs = (tAhb1 - tAhb0) / 1_000_000.0

            val holder = surfaceHolder
            if (buffer != null) {
                try {
                    if (engineReady && foreground && holder != null && holder.surface.isValid) {
                        val targetFarFieldPx = if (state.bokehActive) fStopToTargetFarFieldPx(state.fStop, currentLens) else 0f
                        val tEng0 = SystemClock.elapsedRealtimeNanos()
                        native.renderFrame(
                            buffer,
                            depthFor(image.timestamp),
                            DeviceProfile.DEPTH_WIDTH,
                            DeviceProfile.DEPTH_HEIGHT,
                            matteFor(image.timestamp),
                            256,
                            256,
                            state.fStop,
                            targetFarFieldPx,
                        )
                        val tEng1 = SystemClock.elapsedRealtimeNanos()
                        engineSubmitMs = (tEng1 - tEng0) / 1_000_000.0
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "renderFrame error: ${t.message}")
                } finally {
                    buffer.close()
                }
            }
        } finally {
            image.close()
            buffersReleasedCount++
        }
        val tTotalEnd = SystemClock.elapsedRealtimeNanos()
        val totalMs = (tTotalEnd - t0) / 1_000_000.0

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastCpuFrameLogMs >= 1000) {
            lastCpuFrameLogMs = nowMs
            Log.i(TAG, String.format(Locale.US,
                "CPU_FRAME: sensorCb=%.2fms imageReaderCb=%.2fms ahbImport=%.2fms engineSubmit=%.2fms present=0.00ms total=%.2fms acquired=%d released=%d",
                sensorCbMs, imageReaderCbMs, ahbImportMs, engineSubmitMs, totalMs, buffersAcquiredCount, buffersReleasedCount))
        }

        if (totalMs > 60.0) Log.w(TAG, "slow preview frame: ${totalMs.toLong()}ms")
    }

    private fun onDepthImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (!foreground || !state.sessionReady || disposed) return
            // Y8 rows may be padded. JNI expects tightly packed width × height bytes.
            val plane = image.planes[0]
            val bytes = FrameData.packPlane(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            latestDepth = bytes
            synchronized(depthFrames) {
                depthFrames.addLast(FrameData.DepthFrame(image.timestamp, bytes))
                while (depthFrames.size > 8) depthFrames.removeFirst()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "depth read failed: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun onStillImage(reader: ImageReader) {
        val t2 = SystemClock.elapsedRealtimeNanos()
        Log.i("TIMING_FREEZE", "capture_buffer_received: $t2 ns")
        val image = reader.acquireLatestImage() ?: return
        val frozen = captureState ?: run { image.close(); return }
        main.removeCallbacks(captureTimeout)
        if (image.format == ImageFormat.JPEG) {
            receiveHighResolutionJpeg(image, frozen)
            return
        }
        val frozenW = image.width
        val frozenH = image.height
        var outPath: String? = null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        var t4 = 0L
        val shutterNs = shutterClickNs
        try {
            val t3 = SystemClock.elapsedRealtimeNanos()
            val buffer = try {
                image.hardwareBuffer
            } catch (t: Throwable) {
                null
            }
            var gpuElapsedNs = 0L

            if (buffer != null) {
                val dir = activity.getExternalFilesDir("bokeh_raw") ?: activity.filesDir
                if (!dir.exists()) dir.mkdirs()
                outPath = File(dir, "AUXCAM_$stamp.rgba").absolutePath

                // Sensor RAW is not available from this YUV stream.
                // 50MP vs 12MP is decided by the capture request the service
                // builds; log the intent here so a mismatch is visible.
                Log.i(TAG, "capture resolution=${state.resolution.label}")

                val targetFarFieldPx = if (frozen.bokehActive) fStopToTargetFarFieldPx(frozen.fStop, currentLens) else 0f
                val timestamps = LongArray(4)
                val processed = try { native.processStill(
                    buffer,
                    depthFor(image.timestamp),
                    DeviceProfile.DEPTH_WIDTH,
                    DeviceProfile.DEPTH_HEIGHT,
                    if (frozen.bokehActive) matteFor(image.timestamp) else null,
                    256,
                    256,
                    frozen.fStop,
                    targetFarFieldPx,
                    outPath,
                    timestamps,
                ) } finally { buffer.close() }
                check(processed) { "Native capture processing failed" }
                val fenceCompleteNs = timestamps[1]
                t4 = if (fenceCompleteNs > 0L) fenceCompleteNs else SystemClock.elapsedRealtimeNanos()
                val t3Gpu = if (timestamps[3] > 0L) timestamps[3] else t3
                Log.i("TIMING_FREEZE", "composite_complete: $t4 ns")
                gpuElapsedNs = timestamps[0]

                val dispatchNs = shutterDispatchNs
                val dT1 = (dispatchNs - shutterNs) / 1_000_000
                val dT2 = (t2 - dispatchNs) / 1_000_000
                val dT3 = (t3Gpu - t2) / 1_000_000
                val dT4 = (t4 - t3Gpu) / 1_000_000
                val dTotal = (t4 - shutterNs) / 1_000_000

                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "STILL_RAW_TIMINGS: t0=%d t1=%d t2=%d t3=%d t4=%d | t1-t0=%dms t2-t1=%dms t3-t2=%dms t4-t3=%dms total=%dms",
                        shutterNs,
                        dispatchNs,
                        t2,
                        t3Gpu,
                        t4,
                        dT1,
                        dT2,
                        dT3,
                        dT4,
                        dTotal,
                    ),
                )

                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "STILL shutter→yuv=%dms gpu=%dms (fence %dms) aperture=f/%.1f zoom=%.2f path=%s",
                        (t2 - shutterNs) / 1_000_000,
                        (t4 - t3) / 1_000_000,
                        gpuElapsedNs / 1_000_000,
                        state.fStop,
                        state.zoom,
                        outPath,
                    ),
                )

                val capFrames = capturePreviewFrames
                val maxGapMs = maxPreviewGapNsDuringCapture / 1_000_000
                Log.i(TAG, "PREVIEW_DURING_STILL: framesPresent=$capFrames maxGapMs=${maxGapMs}ms")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "still processing failed", t)
            outPath?.let { File(it).delete() }
            outPath = null
        } finally {
            image.close()
        }

        // L.4: Freeze requested dimensions and expected byte length at capture request time
        val expectedBytes = frozenW.toLong() * frozenH.toLong() * 4L

        val path = outPath
        if (path == null) {
            finishCapture("Capture failed — try again")
        } else {
            io.execute {
                saveStillToMediaStore(path, stamp, shutterNs, t4, frozenW, frozenH, expectedBytes)
            }
        }
    }

    private fun receiveHighResolutionJpeg(image: android.media.Image, frozen: CameraUiState) {
        val expectedW = frozen.highResolutionWidth
        val expectedH = frozen.highResolutionHeight
        try {
            check(frozen.resolution == Resolution.High && !frozen.bokehActive)
            val source = image.planes[0].buffer
            val jpeg = ByteArray(source.remaining())
            source.get(jpeg)
            io.execute { saveHighResolutionJpeg(jpeg, expectedW, expectedH) }
        } catch (e: Exception) {
            Log.e(TAG, "50 MP buffer read failed", e)
            finishCapture("50 MP capture failed")
        } finally {
            image.close()
        }
    }

    private fun saveHighResolutionJpeg(jpeg: ByteArray, expectedW: Int, expectedH: Int) {
        var uri: Uri? = null
        var published = false
        try {
            // Decode headers only. A 50 MP ARGB bitmap would cost ~200 MB and
            // recompressing it would both hurt quality and risk an OOM.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            check(CaptureOutput.is50MpSize(bounds.outWidth, bounds.outHeight) &&
                CaptureOutput.matches(bounds.outWidth, bounds.outHeight, expectedW, expectedH)) {
                "Camera returned ${bounds.outWidth}×${bounds.outHeight}, expected ${expectedW}×${expectedH}"
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "NOTICAM_${stamp}_50MP.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NotiCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.WIDTH, bounds.outWidth)
                put(MediaStore.Images.Media.HEIGHT, bounds.outHeight)
            }
            val destination = checkNotNull(activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            uri = destination
            checkNotNull(activity.contentResolver.openOutputStream(destination)).use { output ->
                output.write(jpeg) // preserve the HAL's full-resolution data and EXIF byte-for-byte
                output.flush()
            }
            val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(activity.contentResolver.update(destination, complete, null, null) > 0)
            published = true
            latestPhotoUri = destination
            buildJpegThumbnail(jpeg, bounds.outWidth, bounds.outHeight)?.let { thumbnail ->
                post { onThumbnailReady(thumbnail) }
            }
            Log.i(TAG, "50 MP saved: ${bounds.outWidth}×${bounds.outHeight}, ${jpeg.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "50 MP save failed", e)
        } finally {
            if (!published) uri?.let { incomplete ->
                try { activity.contentResolver.delete(incomplete, null, null) }
                catch (e: Exception) { Log.w(TAG, "Remove incomplete 50 MP row", e) }
            }
            finishCapture(if (published) "50 MP saved to Photos" else "Could not save 50 MP photo")
        }
    }

    private fun buildJpegThumbnail(jpeg: ByteArray, width: Int, height: Int): ImageBitmap? = try {
        var sample = 1
        while (maxOf(width, height) / sample > 336) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
        if (bitmap == null) null else {
            val orientation = ExifInterface(ByteArrayInputStream(jpeg))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees) }, true)
            if (upright !== bitmap) bitmap.recycle()
            upright.asImageBitmap()
        }
    } catch (e: Exception) {
        Log.w(TAG, "50 MP thumbnail unavailable", e)
        null
    }

    private fun saveStillToMediaStore(
        rgbaPath: String,
        stamp: String,
        shutterNs: Long,
        compositeCompleteNs: Long,
        frozenW: Int,
        frozenH: Int,
        expectedBytes: Long,
    ) {
        val rgbaFile = File(rgbaPath)
        if (!rgbaFile.exists()) {
            Log.e(TAG, "saveStillToMediaStore: RGBA file missing at $rgbaPath")
            finishCapture("Capture output missing")
            return
        }

        // L.4 Guard: Compare actual readback length against expectedBytes before MediaStore insert
        val actualBytes = rgbaFile.length()
        if (actualBytes != expectedBytes) {
            Log.e(
                TAG,
                String.format(
                    Locale.US,
                    "50MP/STILL GATE ABORT: expected %dx%dx4=%d bytes, got %d bytes (diff=%d). Aborting before MediaStore insert.",
                    frozenW,
                    frozenH,
                    expectedBytes,
                    actualBytes,
                    actualBytes - expectedBytes
                )
            )
            rgbaFile.delete()
            finishCapture("Capture size mismatch — photo not saved")
            return
        }

        // 1. Build thumbnail for the viewfinder UI
        val thumbnail = buildThumbnail(rgbaPath, frozenW, frozenH)

        // 2. MediaStore insertion
        val srcW = frozenW
        val srcH = frozenH
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "AUXCAM_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NotiCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        var destUri: Uri? = null
        var saved = false
        try {
            destUri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (destUri == null) {
                Log.e(TAG, "MediaStore insert returned null URI")
                return
            }

            // 3. Encode the sharp/bokeh output without adding a sharpening or denoising pass.
            val bitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
            try {
                RandomAccessFile(rgbaFile, "r").use { raf ->
                    val channel = raf.channel
                    val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, rgbaFile.length())
                    bitmap.copyPixelsFromBuffer(map)
                }
                val quality = if (forceJpeg98) 98 else CAPTURE_JPEG_QUALITY
                val output = checkNotNull(activity.contentResolver.openOutputStream(destUri)) { "Cannot open photo output" }
                output.use { outStream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream)) { "JPEG encoding failed" }
                    outStream.flush()
                }
            } finally {
                bitmap.recycle()
            }
            // EXIF orientation is required: the pixel buffer is sensor-landscape.
            val descriptor = checkNotNull(activity.contentResolver.openFileDescriptor(destUri, "rw")) {
                "Cannot open photo metadata"
            }
            descriptor.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                exif.saveAttributes()
            }

            val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(activity.contentResolver.update(destUri, published, null, null) > 0) { "Cannot publish photo" }
            saved = true
            latestPhotoUri = destUri
            post { thumbnail?.let { onThumbnailReady(it) } }
            val t5 = SystemClock.elapsedRealtimeNanos()
            val fileSize = try {
                activity.contentResolver.openFileDescriptor(destUri, "r")?.use { it.statSize } ?: -1L
            } catch (e: Throwable) { -1L }

            val t0ToT4Ms = (compositeCompleteNs - shutterNs) / 1_000_000
            val t0ToT5Ms = (t5 - shutterNs) / 1_000_000
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "STILL saved to MediaStore: uri=%s size=%d KB | t0->t4=%dms t0->t5=%dms",
                    destUri.toString(),
                    fileSize / 1024,
                    t0ToT4Ms,
                    t0ToT5Ms,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to encode/persist JPEG to MediaStore", t)
        } finally {
            if (!saved) destUri?.let { uri ->
                try { activity.contentResolver.delete(uri, null, null) }
                catch (t: Throwable) { Log.e(TAG, "Failed to remove incomplete photo", t) }
            }
            finishCapture(if (saved) "Saved to Photos" else "Could not save photo — check storage")
            if (!DEBUG_DUMP_RGBA) {
                try {
                    rgbaFile.delete()
                } catch (ignored: Throwable) {}
            }
        }
    }

    /**
     * Samples the engine's raw RGBA dump straight off disk. A full-res 4K frame is
     * ~50 MB, so we seek row by row and only ever keep a 168 × 126 thumbnail —
     * the heap never sees the full bitmap.
     */
    private fun buildThumbnail(rgbaPath: String, srcW: Int, srcH: Int): ImageBitmap? {
        var raf: RandomAccessFile? = null
        return try {
            val file = File(rgbaPath)
            if (!file.exists() || file.length() < srcW.toLong() * srcH * 4L) return null

            raf = RandomAccessFile(file, "r")
            val pixels = IntArray(THUMB_W * THUMB_H)
            val row = ByteArray(srcW * 4)

            for (ty in 0 until THUMB_H) {
                val sy = ((ty + 0.5f) * srcH / THUMB_H).toInt().coerceIn(0, srcH - 1)
                raf.seek(sy.toLong() * srcW * 4L)
                raf.readFully(row)
                for (tx in 0 until THUMB_W) {
                    val sx = ((tx + 0.5f) * srcW / THUMB_W).toInt().coerceIn(0, srcW - 1)
                    val o = sx * 4
                    val r = row[o].toInt() and 0xFF
                    val g = row[o + 1].toInt() and 0xFF
                    val b = row[o + 2].toInt() and 0xFF
                    pixels[ty * THUMB_W + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val landscape = Bitmap.createBitmap(pixels, THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
            // The composite writes the sensor frame (landscape) — rotate for display.
            val matrix = Matrix().apply { postRotate(90f) }
            val portrait = Bitmap.createBitmap(landscape, 0, 0, THUMB_W, THUMB_H, matrix, true)
            if (portrait !== landscape) landscape.recycle()
            portrait.asImageBitmap()
        } catch (t: Throwable) {
            Log.w(TAG, "thumbnail decode failed: ${t.message}")
            null
        } finally {
            try {
                raf?.close()
            } catch (ignored: Throwable) {
                // nothing to do
            }
        }
    }

    private fun onThumbnailReady(bitmap: ImageBitmap) {
        update {
            it.copy(
                lastThumbnail = bitmap,
                galleryPulse = it.galleryPulse + 1,
            )
        }
    }

    // ═══════════════════════════ CAPTURE ═════════════════════════════════════

    private fun finishCapture(message: String) {
        main.removeCallbacks(captureTimeout)
        stillCaptureInProgress.set(false)
        captureState = null
        post { update { it.copy(captureBusy = false, status = message) } }
    }

    private fun triggerCapture(highQuality: Boolean, enableZsl: Boolean) {
        if (!foreground || !state.canTakePhoto || disposed) return
        val svc = service ?: run {
            Log.e(TAG, "triggerCapture failed: service is null")
            update { it.copy(status = "Camera not ready") }
            return
        }
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (nowNs - lastShutterTriggerNs < 1_500_000_000L) {
            Log.w(TAG, "triggerCapture: dropped, 1.5s shutter debounce active (queue depth 1 enforced)")
            return
        }
        if (!stillCaptureInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "triggerCapture: dropped, capture already in flight (queue depth 1 enforced)")
            return
        }
        lastShutterTriggerNs = nowNs
        Log.i(TAG, "triggerCapture: highQuality=$highQuality, enableZsl=$enableZsl")
        captureState = state
        main.postDelayed(captureTimeout, if (state.resolution == Resolution.High) 25_000L else 10_000L)
        maxPreviewGapNsDuringCapture = 0L
        capturePreviewFrames = 0
        shutterClickNs = SystemClock.elapsedRealtimeNanos()
        Log.i("TIMING_FREEZE", "shutter_press: $shutterClickNs ns")
        update {
            it.copy(
                captureBusy = true,
                capturePulse = it.capturePulse + 1,
                status = "Capturing…",
            )
        }
        try {
            svc.triggerStillWithZsl(shutterClickNs, false, enableZsl && state.resolution == Resolution.Standard)
            shutterDispatchNs = SystemClock.elapsedRealtimeNanos()
            Log.i("TIMING_FREEZE", "shutter_dispatched: $shutterDispatchNs ns")
        } catch (e: RemoteException) {
            finishCapture("Capture request failed")
            Log.e(TAG, "triggerStill failed", e)
        }
    }

    private var timerRemaining = 0
    private val timerTick = object : Runnable {
        override fun run() {
            if (!timerRunning || !foreground) return
            if (!state.sessionReady) {
                timerRunning = false
                update { it.copy(captureBusy = false, status = "Camera not ready") }
                return
            }
            if (timerRemaining <= 0) {
                timerRunning = false
                update { it.copy(captureBusy = false) }
                triggerCapture(false, true)
            } else {
                update { it.copy(status = "${timerRemaining}s", captureBusy = true) }
                timerRemaining--
                haptic()
                main.postDelayed(this, 1000L)
            }
        }
    }

    private fun startTimerCapture(remaining: Int) {
        if (timerRunning || stillCaptureInProgress.get() || !state.sessionReady) return
        timerRunning = true
        timerRemaining = remaining
        timerTick.run()
    }

    // ═══════════════════════════ ACTIONS ═════════════════════════════════════

    override fun onFlashClick() {
        val next = when (state.flash) {
            FlashMode.Off -> FlashMode.Auto
            FlashMode.Auto -> FlashMode.On
            FlashMode.On -> FlashMode.Off
        }
        update { it.copy(flash = next, status = flashLabel(next)) }
        haptic()
        try {
            service?.setFlashMode(next.ordinal)
        } catch (e: RemoteException) {
            Log.w(TAG, "setFlashMode failed")
        }
    }

    private fun flashLabel(mode: FlashMode): String = when (mode) {
        FlashMode.Off -> "Flash off"
        FlashMode.Auto -> "Auto flash"
        FlashMode.On -> "Flash on"
    }

    override fun onSettingsToggle() {
        update { it.copy(settingsOpen = !it.settingsOpen) }
        haptic()
    }

    override fun onModeSelected(mode: CaptureMode) {
        if (mode == state.mode || state.captureBusy) return
        invalidateAuxiliaryFrames()
        update { it.copy(mode = mode, bokehEnabled = false, settingsOpen = false,
            status = if (mode == CaptureMode.Video) "Video recording is coming later" else "Ready") }
        haptic()
    }

    /**
     * Tapping a chip selects that stop and leaves the chips on screen.
     *
     * It must never summon the wheel: tapping a chip is a destination, not a
     * gesture, and swapping the control the user just pressed out from under their
     * finger is what made the old build feel jumpy.
     */
    override fun onZoomSelected(zoom: Float) {
        applyZoom(zoom, force = true)
        haptic()
    }

    /** Pinch: comes up first, so even a 1 % squeeze reveals the read-out. */
    override fun onZoomGesture(scaleFactor: Float) {
        showDial()
        if (scaleFactor == 0f) return
        applyZoom(state.zoom * scaleFactor, force = false)
    }

    override fun onZoomDragStart() {
        // No haptic: the chips collapsing into the wheel *is* the feedback, and a
        // pinch does not tick either. A tick here would fire on every scrub.
        showDial()
    }

    override fun onZoomDrag(deltaPx: Float) {
        if (deltaPx == 0f || deltaPx.isNaN()) return
        // Exponential, so the same finger travel moves the same *proportion* of the
        // range at 0.6x and at 2x. ~180 px doubles the zoom.
        applyZoom(state.zoom * exp(deltaPx / 180f), force = false)
        showDial()
    }

    override fun onZoomDragEnd() {
        // Extend the read-out so the number the drag landed on can be read, but
        // never summon one on the way out.
        if (state.zoomDialVisible) armDialHide()
    }

    private fun invalidateAuxiliaryFrames() {
        hasRealMatte = false
        frameGeneration++
        synchronized(depthFrames) { depthFrames.clear() }
        latestDepth = null
    }

    private fun applyZoom(requested: Float, force: Boolean) {
        if (!requested.isFinite() || state.captureBusy || !foreground || disposed) return
        val zoom = requested.coerceIn(DeviceProfile.ZOOM_STOPS.first(), DeviceProfile.ZOOM_STOPS.last())
        if (!force && kotlin.math.abs(zoom - state.zoom) < 0.005f) return
        val lens = when {
            zoom < 0.9f -> "2"
            state.telephoto && zoom >= TELE_THRESHOLD -> "3"
            else -> "0"
        }
        update { it.copy(zoom = zoom) }
        if (state.resolution == Resolution.High && kotlin.math.abs(zoom - 1f) > 0.01f) {
            currentLens = lens
            onTele = lens == "3"
            update { it.copy(resolution = Resolution.Standard) }
            rebuildStillSession("Zoom uses 12 MP. Switching…")
        } else if (lens != currentLens) selectLens(lens)
        if (force || kotlin.math.abs(zoom - lastSentZoom) > 0.02f) {
            lastSentZoom = zoom
            try { service?.setZoom(zoom) } catch (e: RemoteException) { Log.w(TAG, "setZoom failed", e) }
        }
    }

    private fun selectLens(lens: String) {
        if (state.captureBusy || lens == currentLens) return
        currentLens = lens
        if (state.resolution == Resolution.High) {
            update { it.copy(resolution = Resolution.Standard) }
            rebuildStillSession("Lens change uses 12 MP…")
            return
        }
        onTele = lens == "3"
        lensSwitching = true
        invalidateAuxiliaryFrames()
        if (onTele) native.setCameraOpticalParams(11.12f, 5.80f, 2.40f)
        else native.setCameraOpticalParams(5.56f, 8.16f, 1.88f)
        update { it.copy(sessionReady = false, status = "Switching lens…") }
        try { service?.setPhysicalLens(lens) }
        catch (e: RemoteException) {
            lensSwitching = false
            update { it.copy(status = "Lens switch failed") }
        }
    }

    private fun switchToTele() = selectLens("3")
    private fun switchToMain() = selectLens("0")

    /**
     * Brings the radial wheel up and re-arms its timer.
     *
     * Called *only* from the two zoom gestures (pinch, chip-row drag) — never from
     * a zoom value change. That is the whole fix for the wheel appearing when the
     * lens was flipped: the flip resets the zoom to 1x, and a visibility rule
     * keyed on "the zoom changed" cannot tell a reset from a gesture.
     */
    private fun showDial() {
        armDialHide()
        if (!state.zoomDialVisible) {
            update { it.copy(zoomDialVisible = true) }
            // Edge-triggered, so it fires once per summon however many pinch or
            // drag events follow. The wheel taking over is the moment the control
            // changes identity, and it is the only feedback a pinch gets.
            softTick()
        }
    }

    private fun armDialHide() {
        main.removeCallbacks(hideDial)
        main.postDelayed(hideDial, 1600L)
    }

    private val hideDial = Runnable {
        update { it.copy(zoomDialVisible = false) }
    }

    override fun onApertureChanged(fStop: Float) {
        if (!fStop.isFinite()) return
        val clamped = fStop.coerceIn(FSTOPS.first(), FSTOPS.last())
        if (kotlin.math.abs(clamped - state.fStop) < 0.001f) return
        update { it.copy(fStop = clamped) }
    }

    override fun onEvChanged(ev: Float) {
        if (!ev.isFinite()) return
        val clamped = ev.coerceIn(-4f, 4f)
        if (kotlin.math.abs(clamped - state.ev) < 0.01f) return
        update { it.copy(ev = clamped) }
        // The Camera2 control is quantised to 1/3 EV, so only forward when the
        // rounded step actually changes — a smooth UI drag is ~continuous.
        val steps = (clamped * 3f).roundToInt()
        if (steps == lastSentEvSteps) return
        lastSentEvSteps = steps
        try {
            service?.setEvCompensation(steps)
        } catch (e: RemoteException) {
            Log.w(TAG, "setEvCompensation failed")
        }
    }

    override fun onBokehToggle() {
        if (state.mode != CaptureMode.Photo || state.captureBusy || !state.sessionReady) return
        val enabled = !state.bokehEnabled
        invalidateAuxiliaryFrames()
        if (enabled && state.resolution == Resolution.High) {
            update { it.copy(bokehEnabled = true, resolution = Resolution.Standard) }
            rebuildStillSession("Portrait effect uses 12 MP. Switching…")
        } else {
            update { it.copy(bokehEnabled = enabled, status = if (enabled) "Portrait effect on" else "Portrait effect off") }
        }
        haptic()
    }

    override fun onResolutionToggle() {
        if (state.mode != CaptureMode.Photo || state.captureBusy || !state.sessionReady) return
        if (state.resolution == Resolution.High) {
            update { it.copy(resolution = Resolution.Standard) }
            rebuildStillSession("Switching to 12 MP…")
        } else if (!state.supportsHighResolution) {
            update { it.copy(status = "The camera HAL does not advertise a 50 MP JPEG stream") }
        } else {
            // Full-resolution remosaic is a main-camera-only path. Never upscale
            // the 12 MP Vulkan readback or crop the image and still call it 50 MP.
            currentLens = "0"
            onTele = false
            lastSentZoom = 1f
            update { it.copy(resolution = Resolution.High, zoom = 1f, bokehEnabled = false) }
            try { service?.setZoom(1f) } catch (e: RemoteException) { Log.w(TAG, "50 MP zoom reset", e) }
            rebuildStillSession("Opening native 50 MP capture…")
        }
        haptic()
    }

    private fun createStillReader(): ImageReader {
        val high = state.resolution == Resolution.High
        val width = if (high) state.highResolutionWidth else DeviceProfile.STILL_WIDTH
        val height = if (high) state.highResolutionHeight else DeviceProfile.STILL_HEIGHT
        check(!high || CaptureOutput.is50MpSize(width, height)) { "50 MP was not advertised by this camera" }
        val reader = if (high) ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
        else ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        return reader.also { it.setOnImageAvailableListener(::onStillImage, stillHandler) }
    }

    private fun rebuildStillSession(message: String) {
        sessionToken++
        update { it.copy(sessionReady = false, status = message) }
        invalidateAuxiliaryFrames()
        try {
            service?.closeSession()
            val old = stillReader
            old?.setOnImageAvailableListener(null, null)
            stillReader = createStillReader()
            stillHandler?.post { old?.close() }
            startStereoSession()
        } catch (e: Exception) {
            Log.e(TAG, "Still session rebuild failed", e)
            update { it.copy(status = "Could not configure capture stream", sessionReady = false) }
        }
    }

    override fun onTelephotoToggle() {
        if (state.captureBusy || !state.sessionReady) return
        val next = !state.telephoto
        update { it.copy(telephoto = next) }
        haptic()
        if (next && state.zoom >= TELE_THRESHOLD) {
            switchToTele()
        } else if (!next && onTele) {
            switchToMain()
        }
    }

    override fun onFrameRatioCycle() {
        update { it.copy(status = "Capture uses the native 4:3 sensor frame") }
    }

    override fun onFrameRatioSelected(ratio: FrameRatio) {
        update { it.copy(frameRatio = FrameRatio.FourThree, status = "Capture uses the native 4:3 sensor frame") }
    }

    override fun onFocusLock() {
        if (state.aeAfLocked) return
        update { it.copy(aeAfLocked = true) }
        try {
            service?.setAfLock(true)
        } catch (e: RemoteException) {
            Log.w(TAG, "setAfLock(true) failed")
        }
    }

    override fun onFocusUnlock() {
        if (!state.aeAfLocked) return
        update { it.copy(aeAfLocked = false) }
        try {
            service?.setAfLock(false)
        } catch (e: RemoteException) {
            Log.w(TAG, "setAfLock(false) failed")
        }
    }

    override fun onManualFocusChanged(fraction: Float) {
        if (!fraction.isFinite()) return
        val clamped = fraction.coerceIn(0f, 1f)
        update { it.copy(manualFocus = true, focusFraction = clamped) }
        try {
            service?.setManualFocus(clamped)
        } catch (e: RemoteException) {
            Log.w(TAG, "setManualFocus failed")
        }
    }

    override fun onFocusReset() {
        update { it.copy(manualFocus = false, aeAfLocked = false, focusFraction = 0f) }
        haptic()
        try {
            service?.resetFocus()
        } catch (e: RemoteException) {
            Log.w(TAG, "resetFocus failed")
        }
    }

    override fun onGridToggle() {
        update { it.copy(grid = !it.grid) }
        haptic()
    }

    override fun onRawToggle() {
        update { it.copy(raw = false, status = "Sensor RAW / DNG is not supported yet") }
    }

    override fun onTimerCycle() {
        val next = when (state.timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        update { it.copy(timerSeconds = next) }
        haptic()
    }

    override fun onShutterClick() {
        if (!state.canTakePhoto) return
        Log.i(TAG, "onShutterClick called, sessionReady=${state.sessionReady}")
        if (state.timerSeconds > 0) {
            startTimerCapture(state.timerSeconds)
        } else {
            triggerCapture(false, true)
        }
    }

    override fun onGalleryOpen() {
        val uri = latestPhotoUri ?: queryLatestNotiCamUri()
        if (uri != null) {
            Log.i(TAG, "Opening gallery with uri=$uri")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.nothing.gallery")
            }
            try {
                activity.startActivity(intent)
            } catch (t: ActivityNotFoundException) {
                Log.w(TAG, "com.nothing.gallery not found, falling back to chooser: ${t.message}")
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    activity.startActivity(Intent.createChooser(fallbackIntent, "Open photo with"))
                } catch (t2: Throwable) {
                    Toast.makeText(activity, "No gallery app found", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val launchIntent = activity.packageManager.getLaunchIntentForPackage("com.nothing.gallery")
            if (launchIntent != null) {
                activity.startActivity(launchIntent)
            } else {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    activity.startActivity(intent)
                } catch (t: Throwable) {
                    Toast.makeText(activity, "No gallery app found", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun queryLatestNotiCamUri(): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Pictures/NotiCam%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return try {
            activity.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                } else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to query latest NotiCam photo: ${t.message}")
            null
        }
    }

    /**
     * The shader maps upright screen space onto sensor space (see composite.comp),
     * so the tap has to be expressed in the same space before it reaches the HAL.
     */
    override fun onFocusAt(normalisedX: Float, normalisedY: Float) {
        if (state.manualFocus) {
            update { it.copy(manualFocus = false) }
        }
        update { it.copy(focusNonce = it.focusNonce + 1) }
        haptic()
        val u = normalisedX.coerceIn(0f, 1f)
        val v = normalisedY.coerceIn(0f, 1f)
        // Mirrors composite.comp: with PREVIEW_ROTATION_90 the screen tap (u, v)
        // samples the sensor at (v, 1 - u) — a 90° clockwise mapping.
        val sensorX = if (DeviceProfile.PREVIEW_ROTATION_90) v else u
        val sensorY = if (DeviceProfile.PREVIEW_ROTATION_90) 1f - u else v
        try {
            service?.focusAt(sensorX, sensorY)
        } catch (e: RemoteException) {
            Log.w(TAG, "focusAt failed")
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Fallback haptic for state changes the UI cannot feel (lens switch, timer).
     * The composables own gesture feedback through `CameraHaptics`; this is only
     * for controller-initiated events.
     */
    /**
     * A single wheel tick — the softest event in the vocabulary.
     *
     * The controller-side twin of `CameraHaptics.tick()`: the platform's own
     * segment tick on API 34+, `CLOCK_TICK` below that. Deliberately *not*
     * [haptic], which is capture-strength (`CONFIRM`) and would be far too heavy
     * for a control appearing — and it never reaches for the vibrator.
     */
    private fun softTick() {
        try {
            val view = activity.window.decorView
            if (Build.VERSION.SDK_INT >= 34) {
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "softTick failed")
        }
    }

    private fun haptic() {
        try {
            val view = activity.window.decorView
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "no haptics: ${t.message}")
        }
    }

    private fun onThermalChanged(status: Int) {
        Log.i(TAG, "thermal status $status")
        matteSegmenter?.onThermalStatusChanged(status)
        val severe = status >= PowerManager.THERMAL_STATUS_SEVERE
        native.setThermalThrottled(severe)
        update { it.copy(telemetry = "THERMAL · " + thermalName(status) + if (severe) " · throttled" else "") }
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN"
    }

    private fun discoverLenses() {
        physicalLenses.clear()
        try {
            val cm = activity.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val chars = cm?.getCameraCharacteristics(DeviceProfile.CAMERA_ID)
            chars?.physicalCameraIds?.let { ids ->
                physicalLenses.addAll(ids)
                physicalLenses.sort()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "discoverLenses: ${t.message}")
        }

        // These are the device IDs used by the privileged CMF service, not
        // indices into lexically sorted physical IDs of a logical camera.
        if (DeviceProfile.isTargetDevice()) {
            physicalLenses.clear()
            physicalLenses.addAll(listOf("0", "2", "3"))
        }
        val defaultIndex = physicalLenses.indexOf("0")
        currentLens = "0"
        update {
            it.copy(
                lensCount = physicalLenses.size.coerceAtLeast(1),
                lensIndex = defaultIndex.coerceAtLeast(0),
            )
        }
        Log.i(TAG, "physical lenses: $physicalLenses (current=$currentLens)")
    }

    private fun dumpPreviewDiagnostics(image: android.media.Image) {
        try {
            val yPlane = image.planes[0]
            val yBuf = yPlane.buffer
            val w = image.width
            val h = image.height
            val rowStride = yPlane.rowStride

            val outDir = activity.getExternalFilesDir("diag") ?: activity.filesDir
            if (!outDir.exists()) outDir.mkdirs()

            // 1. Save fg_sanity_luma.png
            val lumaBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val lumaPixels = IntArray(w * h)
            for (y in 0 until h) {
                val rowOff = y * rowStride
                for (x in 0 until w) {
                    val yVal = yBuf.get(rowOff + x).toInt() and 0xFF
                    lumaPixels[y * w + x] = (0xFF shl 24) or (yVal shl 16) or (yVal shl 8) or yVal
                }
            }
            lumaBmp.setPixels(lumaPixels, 0, w, 0, 0, w, h)
            FileOutputStream(File(outDir, "fg_sanity_luma.png")).use {
                lumaBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            FileOutputStream(File(outDir, "luma_current.png")).use {
                lumaBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            lumaBmp.recycle()

            // 2. Save alpha_preview.png, fg_sanity_alpha.png, and alpha_stretched_binding2.png
            val alphaBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val alphaPixels = IntArray(256 * 256)
            var fg045RawCount = 0
            var fg065StretchedCount = 0
            for (i in 0 until 256 * 256) {
                val aVal = latestMatte[i].toInt() and 0xFF
                val pStretched = aVal / 255.0f
                val pRaw = pStretched * 0.5f
                if (pRaw >= 0.45f) fg045RawCount++
                if (pStretched >= 0.65f) fg065StretchedCount++
                alphaPixels[i] = (0xFF shl 24) or (aVal shl 16) or (aVal shl 8) or aVal
            }
            alphaBmp.setPixels(alphaPixels, 0, 256, 0, 0, 256, 256)
            FileOutputStream(File(outDir, "fg_sanity_alpha.png")).use {
                alphaBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            FileOutputStream(File(outDir, "alpha_preview.png")).use {
                alphaBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            FileOutputStream(File(outDir, "alpha_stretched_binding2.png")).use {
                alphaBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            alphaBmp.recycle()

            val fg045Raw = fg045RawCount.toFloat() / (256f * 256f)
            val fg065Stretched = fg065StretchedCount.toFloat() / (256f * 256f)
            Log.i(TAG, String.format(Locale.US, "L8_SANITY: fg045_raw=%.4f fg065_stretched=%.4f", fg045Raw, fg065Stretched))

            // 3. Save depth_invalid_mask.png and coc_map_preview.png
            val depthBytes = latestDepth
            if (depthBytes != null && depthBytes.isNotEmpty()) {
                val dW = DeviceProfile.DEPTH_WIDTH
                val dH = DeviceProfile.DEPTH_HEIGHT
                val invBmp = Bitmap.createBitmap(dW, dH, Bitmap.Config.ARGB_8888)
                val cocBmp = Bitmap.createBitmap(dW, dH, Bitmap.Config.ARGB_8888)
                val invPixels = IntArray(dW * dH)
                val cocPixels = IntArray(dW * dH)
                var y8ZeroCount = 0
                val s1 = 0.6f

                for (i in 0 until dW * dH) {
                    val y8 = depthBytes[i].toInt() and 0xFF
                    if (y8 == 0) {
                        y8ZeroCount++
                        invPixels[i] = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF // white
                        cocPixels[i] = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
                    } else {
                        invPixels[i] = (0xFF shl 24) // black
                        val disp = y8.toFloat()
                        val zm = (5.56f * 15.0f) / (disp * 0.8571f + 0.0713f)
                        val ratio = if (zm < s1) 0f else Math.abs(zm - s1) / zm
                        val cocPx = (ratio * 255f).toInt().coerceIn(0, 255)
                        cocPixels[i] = (0xFF shl 24) or (cocPx shl 16) or (cocPx shl 8) or cocPx
                    }
                }
                invBmp.setPixels(invPixels, 0, dW, 0, 0, dW, dH)
                cocBmp.setPixels(cocPixels, 0, dW, 0, 0, dW, dH)

                FileOutputStream(File(outDir, "depth_invalid_mask.png")).use {
                    invBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                FileOutputStream(File(outDir, "coc_map_preview.png")).use {
                    cocBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                invBmp.recycle()
                cocBmp.recycle()

                val y8ZeroPct = (y8ZeroCount.toFloat() / (dW * dH)) * 100f
                Log.i(TAG, String.format(Locale.US, "L3_PREVIEW_DUMP: y8_zero_pct=%.2f%% path=%s", y8ZeroPct, outDir.absolutePath))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "dumpPreviewDiagnostics failed", t)
        }
    }
}
