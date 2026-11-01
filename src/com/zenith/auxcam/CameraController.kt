package com.zenith.auxcam

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
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
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.roundToInt
import rikka.shizuku.Shizuku

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
    fun onBokehModelSelected(model: BokehModel) {}
    fun onRunBokehChecks(allModels: Boolean) {}
    fun onCancelBokehChecks() {}
    fun onCopyBokehChecks() {}
    fun onModeSelected(mode: CaptureMode)
    fun onZoomBlendFinished(token: Int) {}
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
    /** Opens the built-in, app-owned media viewer. */
    fun onGalleryOpen()
    fun onGalleryClose()
    fun onCameraReport()

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

        /** Apple's Portrait aperture ladder: f/1.4 → f/16. */
        val FSTOPS = floatArrayOf(
            1.4f, 1.6f, 1.8f, 2.0f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4.0f, 4.5f,
            5.0f, 5.6f, 6.3f, 7.1f, 8.0f, 9.0f, 10f, 11f, 13f, 14f, 16f,
        )
    }

    // ── UI state ────────────────────────────────────────────────────────────
    private val bokehPreferences = activity.getSharedPreferences("bokeh_models", Context.MODE_PRIVATE)
    @Volatile private var selectedBokehModel = BokehModel.fromId(bokehPreferences.getString("model", null))
    private val _uiState = mutableStateOf(
        CameraUiState(
            bokehModel = selectedBokehModel,
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

    @Volatile private var colorReader: ImageReader? = null
    private var depthReader: ImageReader? = null
    private var stillReader: ImageReader? = null

    private var service: IAuxCamService? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var serviceBinding = false
    @Volatile private var sessionToken = 0
    private var pendingRebuildToken: Int? = null
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
    private val bokehProcessor = BokehProcessor(factory = { createBokehEngine(activity.applicationContext, it) })
    @Volatile private var bokehModelGeneration = 0
    @Volatile private var bokehCheckGeneration = 0
    private data class BokehCheckRequest(val token: Int, val models: List<BokehModel>)
    private val pendingBokehChecks = AtomicReference<BokehCheckRequest?>(null)
    @Volatile private var bokehCheckCancelled = false
    @Volatile private var bokehThermal = 0
    @Volatile private var bokehSensorFps = 30f
    @Volatile private var bokehLatestSensorTimestamp = 0L
    private val bokehFrameTimeout = Runnable {
        if (pendingBokehChecks.getAndSet(null) != null) {
            ++bokehCheckGeneration
            update { it.copy(bokehChecksRunning = false, bokehCheckStatus = "No fresh camera frame received. Reopen Photo and retry.") }
        }
    }
    @Volatile private var latestMatte = ByteArray(256 * 256)
    @Volatile
    private var hasRealMatte = false
    private val matteExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "MatteWorkerThread").apply { priority = Thread.NORM_PRIORITY }
    }
    private val isMatteWorkerBusy = AtomicBoolean(false)
    private var lastMatteDispatchMs = 0L
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
    private var latestPendingDngUri: Uri? = null
    private var lensSwitching = false
    @Volatile private var zoomHandoffPending = false
    @Volatile private var zoomHandoffToken = 0
    private var zoomHandoffOrigin = 1f
    private var zoomHandoffCommitted = false
    private var queuedZoom: Float? = null
    @Volatile private var blendFreshFrames = 0
    @Volatile private var lastRenderedSessionToken = -1
    private var deliveredFrameCount = 0
    private var renderedCounterToken = -1
    private var deliveredFrameWindowMs = 0L
    private val preview30Fallbacks = mutableSetOf<String>()
    private fun previewKey() = "$currentLens/${state.resolution}/${state.bokehActive}/${recordingSurface != null}"
    private val previewFrameTimeout = Runnable {
        if (foreground && !disposed && !state.galleryOpen &&
            (!state.sessionReady || lastRenderedSessionToken != sessionToken || (zoomHandoffPending && blendFreshFrames < 4))) {
            onSessionError("No fresh viewfinder frames for the requested preview profile")
        }
    }

    private fun cancelZoomHandoff() {
        if (zoomHandoffPending && !zoomHandoffCommitted) update { it.copy(zoom = zoomHandoffOrigin) }
        ++zoomHandoffToken
        zoomHandoffPending = false
        queuedZoom = null
        main.removeCallbacks(previewFrameTimeout)
        update { it.copy(zoomBlendImage = null, zoomBlendPending = false, zoomBlendReady = false) }
    }

    /** Copy only on a physical handoff, never per preview frame or for a saved still. */
    private fun beginZoomHandoff(fromZoom: Float, toZoom: Float, switch: (Float) -> Unit) {
        val token = ++zoomHandoffToken
        zoomHandoffPending = true
        zoomHandoffOrigin = fromZoom
        zoomHandoffCommitted = false
        blendFreshFrames = 0
        update { it.copy(sessionReady = false, zoom = toZoom, zoomBlendToken = token,
            zoomBlendPending = true, zoomBlendReady = false, zoomBlendScale = toZoom / fromZoom) }
        val committed = AtomicBoolean(false)
        fun commit(snapshot: Bitmap?) {
            if (!committed.compareAndSet(false, true)) { snapshot?.recycle(); return }
            if (disposed || !foreground || token != zoomHandoffToken) { snapshot?.recycle(); return }
            update { it.copy(zoomBlendImage = snapshot?.asImageBitmap()) }
            // Allow Compose to place the freeze layer before closing the old camera.
            activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation {
                    if (!disposed && foreground && token == zoomHandoffToken) {
                        // Consume the latest pinch target before opening a camera,
                        // rather than always opening the first boundary crossed.
                        val target = queuedZoom ?: toZoom
                        queuedZoom = null
                        update { it.copy(zoom = target, zoomBlendScale = target / fromZoom) }
                        zoomHandoffCommitted = true
                        switch(target)
                    }
                }
            }
        }
        val holder = surfaceHolder
        if (holder == null || !holder.surface.isValid) { commit(null); return }
        val frame = holder.surfaceFrame
        val width = frame.width().coerceIn(1, 1080)
        val height = (width.toLong() * frame.height().coerceAtLeast(1) / frame.width().coerceAtLeast(1)).toInt().coerceIn(1, 1920)
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            commit(null)
            return
        } catch (_: Exception) {
            commit(null)
            return
        }
        // PixelCopy can fail before the first present. Never block a handoff waiting for it.
        val timeout = Runnable { commit(null) }
        main.postDelayed(timeout, 200)
        try {
            PixelCopy.request(holder.surface, bitmap, { result ->
                main.removeCallbacks(timeout)
                if (result == PixelCopy.SUCCESS) commit(bitmap)
                else { bitmap.recycle(); commit(null) }
            }, main)
        } catch (_: Exception) { main.removeCallbacks(timeout); bitmap.recycle(); commit(null) }
    }

    override fun onZoomBlendFinished(token: Int) {
        if (token != zoomHandoffToken) return
        zoomHandoffPending = false
        update { it.copy(zoomBlendImage = null, zoomBlendPending = false, zoomBlendReady = false) }
        val next = queuedZoom
        queuedZoom = null
        if (next != null) applyZoom(next, force = true)
    }


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
    @Volatile private var videoGeneration = 0
    private var videoCapture: VideoCapture? = null // IO thread only
    @Volatile private var recordingSurface: Surface? = null
    private var microphonePermissionAsked = false
    private var recordingStartedAt = 0L
    private val videoTick = object : Runnable {
        override fun run() {
            if (!state.videoRecording) return
            update { it.copy(videoElapsedSeconds = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000) }
            main.postDelayed(this, 500)
        }
    }
    private val videoStartTimeout = Runnable {
        if (state.videoStarting) stopVideo(save = false, restore = true)
    }

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
        cancelZoomHandoff()
        if (state.videoStarting || state.videoRecording) stopVideo(save = false, restore = false)
        ++sessionToken
        pendingRebuildToken = null
        serviceBinding = false
        service = null
        update { it.copy(status = "Reconnecting…", sessionReady = false) }
    }

    // ═══════════════════════════ LIFECYCLE ══════════════════════════════════

    fun start() {
        if (disposed) return
        foreground = true
        if (started) {
            if (!engineReady) surfaceHolder?.takeIf { it.surface.isValid }?.let { initializePreviewEngine(it) }
            if (!state.sessionReady) rebuildStillSession("Resuming native capture…")
            return
        }
        started = true

        discoverLenses()
        startReaderThread()
        setupImageReaders()
        if (!engineReady) surfaceHolder?.takeIf { it.surface.isValid }?.let { initializePreviewEngine(it) }

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
        if (disposed || !foreground) return
        foreground = false // Reject queued reads/opens before releasing any resources.
        engineReady = false
        abandonBokehChecks()
        invalidateAuxiliaryFrames()
        cancelZoomHandoff()
        if (state.videoStarting || state.videoRecording) stopVideo(save = state.videoRecording, restore = false)
        sessionToken++
        pendingRebuildToken = null
        matteExecutor.execute { bokehProcessor.close() }
        timerRunning = false
        main.removeCallbacks(timerTick)
        try { service?.closeSession() } catch (e: RemoteException) { Log.w(TAG, "pause session", e) }
        native.destroyEngine() // Release Vulkan imports/swapchain/window while stopped.
        update { it.copy(sessionReady = false, captureBusy = state.captureBusy || stillCaptureInProgress.get(), status = "Camera paused") }
    }

    fun dispose() {
        if (disposed) return
        abandonBokehChecks()
        if (state.videoStarting || state.videoRecording) stopVideo(save = state.videoRecording, restore = false)
        cancelZoomHandoff()
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

        val exitingService = service
        // destroy performs orderly camera closure on the sidecar's handler and
        // has its own process-exit watchdog; don't wait for its HAL on the UI thread.
        try { exitingService?.destroy() } catch (_: RemoteException) { /* Dedicated service may exit before replying. */ }
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
        // Close after any in-flight native inference, on its owning executor.
        matteExecutor.execute { bokehProcessor.close() }
        matteExecutor.shutdown()
        // Copied inputs have no camera/native-engine ownership. Do not block
        // Activity teardown waiting for an uninterruptible model/NNAPI call.
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
        if (initializePreviewEngine(holder) && service != null) startStereoSession()
    }

    private fun initializePreviewEngine(holder: SurfaceHolder): Boolean {
        if (!foreground || disposed || state.galleryOpen || !holder.surface.isValid) return false
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
        return ok
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
        if (surfaceHolder !== holder) return
        cancelZoomHandoff()
        if (state.videoStarting || state.videoRecording) stopVideo(save = state.videoRecording, restore = false)
        ++sessionToken
        pendingRebuildToken = null
        engineReady = false
        surfaceHolder = null
        try { service?.closeSession() } catch (_: RemoteException) { }
        native.destroyEngine()
        update { it.copy(sessionReady = false) }
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
            .tag("noticam-${android.os.Process.myPid()}-${System.identityHashCode(this)}")
            .version(7)
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
        rebuildStillSession("Opening native JPEG capture…")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        cancelZoomHandoff()
        if (state.videoStarting || state.videoRecording) stopVideo(save = false, restore = false)
        ++sessionToken
        pendingRebuildToken = null
        serviceBinding = false
        service = null
        update { it.copy(status = "Disconnected", sessionReady = false) }
    }

    // ═══════════════════════════ SESSION ═════════════════════════════════════

    private fun setupImageReaders() {
        val handler = readerHandler ?: return

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

    private fun configurePreviewReader(svc: IAuxCamService) {
        val prefer60 = previewKey() !in preview30Fallbacks && recordingSurface == null
        val profile = svc.getPreviewConfiguration(currentLens, state.bokehActive,
            state.resolution == Resolution.High, prefer60)
        check(profile.size == 4 && profile[0] > 0 && profile[1] > 0) { "No advertised full-field preview profile" }
        val old = colorReader
        // A fresh queue excludes pre-switch buffers from the new-frame handoff signal.
        colorReader = ImageReader.newInstance(profile[0], profile[1], ImageFormat.YUV_420_888, 3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN).also {
            it.setOnImageAvailableListener(::onColorImage, readerHandler)
        }
        old?.setOnImageAvailableListener(null, null)
        readerHandler?.post { old?.close() }
        update { it.copy(previewWidth = profile[0], previewHeight = profile[1], previewTargetFps = profile[3],
            previewMeasuredFps = 0f,
            previewFallback = when {
                recordingSurface != null -> "30 fps while recording the 30 fps video stream"
                !prefer60 -> "60 fps session unavailable; full-quality still retained"
                profile[1] < 1080 -> "1080-line/60 fps profile not exposed; smaller preview only, full-quality still retained"
                profile[3] < 60 -> "60 fps not advertised at this preview size; full-quality still retained"
                else -> "1080-line full-field preview · 60 fps target"
            }) }
    }

    private fun startStereoSession() {
        if (pendingRebuildToken != null) return
        if (!foreground || disposed || !engineReady || state.galleryOpen) return
        val svc = service ?: return
        val holder = surfaceHolder ?: return
        try { configurePreviewReader(svc) } catch (e: Exception) {
            cancelZoomHandoff()
            update { it.copy(sessionReady = false, status = "Preview configuration failed: ${e.message}") }
            return
        }
        val color = colorReader ?: return
        val depth = depthReader ?: return
        val still = stillReader ?: return
        val stillW = still.width
        val stillH = still.height

        // The preview surface must exist before the engine is initialised.
        if (!holder.surface.isValid) return

        try {
            val token = ++sessionToken
            main.removeCallbacks(previewFrameTimeout)
            main.postDelayed(previewFrameTimeout, 6000)
            svc.setCallback(object : IAuxCamCallback.Stub() {
                override fun onSessionReady(cameraId: String?) { post { if (token == sessionToken) this@CameraController.onSessionReady(cameraId) } }
                override fun onSessionError(msg: String?) { post { if (token == sessionToken) this@CameraController.onSessionError(msg) } }
                override fun onStillCaptured() { post { if (token == sessionToken) this@CameraController.onStillCaptured() } }
                override fun onStats(colorFps: Float, depthFps: Float, dt: Float) {
                    post { if (token == sessionToken) this@CameraController.onStats(colorFps, depthFps, dt) }
                }
            })

            val ok = svc.openStereoSession(
                color.surface, color.width, color.height,
                depth.surface, DeviceProfile.DEPTH_WIDTH, DeviceProfile.DEPTH_HEIGHT,
                still.surface, stillW, stillH, still.imageFormat,
                currentLens, recordingSurface.takeIf { state.videoStarting || state.videoRecording },
                state.mode == CaptureMode.Video, state.zoom,
                if (state.mode == CaptureMode.Video && state.videoTargetFps >= 60) 60 else state.previewTargetFps,
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
        if (!foreground || disposed || state.galleryOpen || !engineReady) return
        main.removeCallbacks(previewFrameTimeout)
        main.postDelayed(previewFrameTimeout, 4000)
        blendFreshFrames = 0
        val actualLens = cameraId ?: "0"
        if (actualLens != currentLens) {
            currentLens = actualLens
            invalidateAuxiliaryFrames()
        }
        if (state.mode == CaptureMode.Photo) refreshHighResolutionCapability()
        onTele = currentLens == "3"
        if (onTele) native.setCameraOpticalParams(11.12f, 5.80f, 2.40f)
        else native.setCameraOpticalParams(5.56f, 8.16f, 1.88f)
        try {
            if (state.manualFocus) service?.setManualFocus(state.focusFraction)
            else if (state.aeAfLocked) service?.setAfLock(true)
        } catch (e: RemoteException) { Log.w(TAG, "Restore focus controls", e) }
        val actualZoom = if (currentLens == "2") state.zoom.coerceIn(.6f, .9999f) else if (currentLens == "3") state.zoom.coerceAtLeast(2f) else state.zoom.coerceAtLeast(1f)
        update {
            it.copy(
                zoom = actualZoom,
                status = "Ready",
                sessionReady = true,
                lensIndex = physicalLenses.indexOf(currentLens).coerceAtLeast(0),
                lensCount = physicalLenses.size.coerceAtLeast(1),
            )
        }
        if (state.mode == CaptureMode.Video && !state.videoStarting && !state.videoRecording) {
            val lens = currentLens
            val generation = sessionToken
            val svc = service
            update { it.copy(videoWidth = 0, videoHeight = 0) }
            io.execute {
                val size = try { svc?.getVideoSize(lens) } catch (_: Exception) { null }
                post { if (generation == sessionToken && state.mode == CaptureMode.Video && currentLens == lens) {
                    update { it.copy(videoWidth = size?.getOrNull(0) ?: 0, videoHeight = size?.getOrNull(1) ?: 0,
                        status = if (size?.size == 3) "Ready · native-aspect video" else "Native-aspect recorder unavailable on this lens") }
                } }
            }
        }
        if (state.videoStarting && recordingSurface != null) {
            val generation = videoGeneration
            io.execute {
                try {
                    if (generation != videoGeneration || !foreground) return@execute
                    videoCapture?.start() ?: error("Recorder disappeared")
                    post {
                        if (generation == videoGeneration && foreground) {
                            main.removeCallbacks(videoStartTimeout)
                            recordingStartedAt = SystemClock.elapsedRealtime()
                            update { it.copy(videoStarting = false, videoRecording = true, captureBusy = false,
                                videoElapsedSeconds = 0, status = if (it.videoHasAudio) "Recording with audio" else "Recording • microphone off") }
                            videoTick.run()
                        }
                    }
                } catch (e: Exception) {
                    post { if (generation == videoGeneration) stopVideo(save = false, restore = true) }
                }
            }
        }
    }

    private fun refreshHighResolutionCapability() {
        val svc = service ?: return
        val generation = frameGeneration
        val lens = currentLens
        io.execute {
            val size = try { svc.getHighResolutionJpegSize() } catch (e: RemoteException) { null }
            post {
                if (generation == frameGeneration) {
                    val width = if (lens == "3") 8192 else (size?.getOrNull(0) ?: 8160)
                    val height = size?.getOrNull(1) ?: 6144
                    update { it.copy(highResolutionWidth = width, highResolutionHeight = height, highResolutionChecked = true,
                        status = if (it.status.startsWith("Checking all exposed") || it.status.startsWith("Checking 50 MP")) {
                            if (CaptureOutput.is50MpSize(width, height)) "50 MP available • tap resolution to enable"
                            else "50 MP not exposed on this lens • share Camera report from controls"
                        } else it.status) }
                }
            }
        }
    }

    private fun onSessionError(msg: String?) {
        main.removeCallbacks(previewFrameTimeout)
        // Never make a 60 fps retry shrink the JPEG or switch to a preview screenshot.
        if (!state.captureBusy && state.previewTargetFps > 30 && preview30Fallbacks.add(previewKey())) {
            rebuildStillSession("60 fps preview rejected; retrying at 30 fps with the same still quality…")
            return
        }
        cancelZoomHandoff()
        if (state.videoStarting || state.videoRecording) {
            stopVideo(save = false, restore = true)
            Toast.makeText(activity, msg ?: "Video session failed", Toast.LENGTH_LONG).show()
            return
        }
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
            update { it.copy(zoom = 1f) }
            rebuildStillSession("Lens unavailable; returned to 1× without digital cropping")
        }
    }

    private fun onStillCaptured() {
        val pendingUri = latestPendingDngUri
        if (pendingUri != null) {
            latestPendingDngUri = null
            io.execute {
                try {
                    val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    activity.contentResolver.update(pendingUri, complete, null, null)
                    latestPhotoUri = pendingUri
                    if (Build.VERSION.SDK_INT >= 29) {
                        try {
                            val source = ImageDecoder.createSource(activity.contentResolver, pendingUri)
                            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                                var sample = 1
                                val maxDim = maxOf(info.size.width, info.size.height)
                                while (maxDim / sample > 336) sample *= 2
                                decoder.setTargetSampleSize(sample)
                            }
                            post { onThumbnailReady(bitmap.asImageBitmap()) }
                        } catch (t: Throwable) {
                            Log.w(TAG, "DNG thumbnail generation failed", t)
                        }
                    }
                    Log.i(TAG, "DNG capture saved successfully to MediaStore: $pendingUri")
                    finishCapture("RAW (DNG) saved")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed finalizing DNG MediaStore entry", e)
                    finishCapture("Failed saving RAW (DNG)")
                }
            }
        }
    }

    private fun onStats(colorFps: Float, depthFps: Float, dt: Float) {
        bokehSensorFps = colorFps
        val text = String.format(
            Locale.US,
            "RENDERED · %.0f FPS · SENSOR · %.0f FPS · DEPTH · %.0f FPS · Δt %.1f ms",
            state.previewMeasuredFps,
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
        if (reader !== colorReader) return
        val renderToken = sessionToken
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
            dispatchBokeh(image)

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
                    if (reader === colorReader && renderToken == sessionToken && engineReady && foreground && holder != null && holder.surface.isValid) {
                        val targetFarFieldPx = if (state.bokehActive) fStopToTargetFarFieldPx(state.fStop, currentLens) else 0f
                        val tEng0 = SystemClock.elapsedRealtimeNanos()
                        val rendered = native.renderFrame(
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
                        if (rendered && reader === colorReader && renderToken == sessionToken) {
                            lastRenderedSessionToken = renderToken
                            val count = ++blendFreshFrames
                            val handoff = zoomHandoffToken
                            if (count == 4) post {
                                if (renderToken == sessionToken) {
                                    main.removeCallbacks(previewFrameTimeout)
                                    lensSwitching = false
                                    if (zoomHandoffPending && handoff == zoomHandoffToken) {
                                        val destination = queuedZoom
                                        if (destination != null && CameraZoom.lensForZoom(destination) != currentLens) {
                                            queuedZoom = null
                                            update { it.copy(zoom = destination, zoomBlendScale = destination / zoomHandoffOrigin,
                                                zoomBlendReady = false, zoomBlendPending = true) }
                                            selectLens(CameraZoom.lensForZoom(destination))
                                            return@post
                                        }
                                        if (state.zoomBlendImage == null) onZoomBlendFinished(handoff)
                                        else update { it.copy(zoomBlendReady = true, zoomBlendPending = false) }
                                    }
                                }
                            }
                            val nowMs = SystemClock.elapsedRealtime()
                            if (renderedCounterToken != renderToken) {
                                renderedCounterToken = renderToken
                                deliveredFrameWindowMs = nowMs
                                deliveredFrameCount = 0
                            }
                            deliveredFrameCount++
                            val elapsed = nowMs - deliveredFrameWindowMs
                            if (deliveredFrameWindowMs == 0L) { deliveredFrameWindowMs = nowMs; deliveredFrameCount = 0 }
                            else if (elapsed >= 1000) {
                                val fps = deliveredFrameCount * 1000f / elapsed
                                deliveredFrameWindowMs = nowMs; deliveredFrameCount = 0
                                post { if (renderToken == sessionToken) update { it.copy(previewMeasuredFps = fps) } }
                            }
                        }
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
            receiveCameraJpeg(image, frozen)
            return
        }
        if (image.format == ImageFormat.RAW_SENSOR) {
            image.close()
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

    private fun receiveCameraJpeg(image: android.media.Image, frozen: CameraUiState) {
        val expectedW = frozen.photoWidth
        val expectedH = frozen.photoHeight
        try {
            check(!frozen.bokehActive)
            val source = image.planes[0].buffer
            val jpeg = ByteArray(source.remaining())
            source.get(jpeg)
            io.execute { saveCameraJpeg(jpeg, expectedW, expectedH, frozen.resolution == Resolution.High) }
        } catch (e: Exception) {
            Log.e(TAG, "JPEG buffer read failed", e)
            finishCapture("JPEG capture failed")
        } finally {
            image.close()
        }
    }

    private fun saveCameraJpeg(jpeg: ByteArray, expectedW: Int, expectedH: Int, high: Boolean) {
        var uri: Uri? = null
        var published = false
        try {
            // Decode headers only. A 50 MP ARGB bitmap would cost ~200 MB and
            // recompressing it would both hurt quality and risk an OOM.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            check((!high || CaptureOutput.is50MpSize(bounds.outWidth, bounds.outHeight)) &&
                CaptureOutput.matches(bounds.outWidth, bounds.outHeight, expectedW, expectedH)) {
                "Camera returned ${bounds.outWidth}×${bounds.outHeight}, expected ${expectedW}×${expectedH}"
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "NOTICAM_${stamp}${if (high) "_50MP" else ""}.jpg")
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
            Log.i(TAG, "JPEG saved: ${bounds.outWidth}×${bounds.outHeight}, ${jpeg.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "JPEG save failed", e)
        } finally {
            if (!published) uri?.let { incomplete ->
                try { activity.contentResolver.delete(incomplete, null, null) }
                catch (e: Exception) { Log.w(TAG, "Remove incomplete 50 MP row", e) }
            }
            finishCapture(if (published) "Photo saved" else "Could not save photo")
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
        val pendingDng = latestPendingDngUri
        if (pendingDng != null && message.contains("fail", ignoreCase = true)) {
            latestPendingDngUri = null
            io.execute {
                try { activity.contentResolver.delete(pendingDng, null, null) } catch (ignored: Exception) {}
            }
        }
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
        Log.i(TAG, "triggerCapture: highQuality=$highQuality, enableZsl=$enableZsl, raw=${state.raw}")
        captureState = state
        main.postDelayed(captureTimeout, if (state.resolution == Resolution.High || state.raw) 25_000L else 10_000L)
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
            if (state.raw) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "NOTICAM_${stamp}_RAW.dng")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NotiCam")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.WIDTH, state.photoWidth)
                    put(MediaStore.Images.Media.HEIGHT, state.photoHeight)
                }
                val dngUri = checkNotNull(activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                latestPendingDngUri = dngUri
                val pfd = checkNotNull(activity.contentResolver.openFileDescriptor(dngUri, "w"))
                svc.triggerStill(shutterClickNs, pfd)
            } else {
                svc.triggerStillWithZsl(shutterClickNs, false, enableZsl && state.resolution == Resolution.Standard)
            }
            shutterDispatchNs = SystemClock.elapsedRealtimeNanos()
            Log.i("TIMING_FREEZE", "shutter_dispatched: $shutterDispatchNs ns")
        } catch (e: Exception) {
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

    override fun onBokehModelSelected(model: BokehModel) {
        if (disposed || state.captureBusy || timerRunning || state.bokehChecksRunning || state.videoStarting || state.videoRecording || state.previewTransitioning) return
        selectedBokehModel = model
        ++bokehModelGeneration
        hasRealMatte = false
        bokehPreferences.edit().putString("model", model.id).apply()
        matteExecutor.execute { bokehProcessor.close() }
        update { it.copy(bokehModel = model, bokehModelReady = false, bokehModelError = null,
            bokehModelStatus = "Selected ${model.label} · loads on next portrait frame") }
        haptic()
    }

    override fun onRunBokehChecks(allModels: Boolean) {
        if (disposed || state.captureBusy || timerRunning || state.bokehChecksRunning || state.videoStarting || state.videoRecording || state.previewTransitioning) return
        if (!foreground || !state.sessionReady || state.mode != CaptureMode.Photo) {
            update { it.copy(bokehCheckStatus = "Open Photo with a live camera preview, then retry.") }
            return
        }
        if (bokehThermal >= PowerManager.THERMAL_STATUS_SEVERE) {
            update { it.copy(bokehCheckStatus = "Phone is too hot. Let it cool before model checks.") }
            return
        }
        ++bokehCheckGeneration
        bokehCheckCancelled = false
        update { it.copy(bokehChecksRunning = true, bokehCheckResults = emptyList(),
            bokehCheckStatus = "Waiting for one fresh camera frame…", bokehModelError = null) }
        pendingBokehChecks.set(BokehCheckRequest(bokehCheckGeneration,
            if (allModels) BokehModel.entries.toList() else listOf(selectedBokehModel)))
        main.removeCallbacks(bokehFrameTimeout)
        main.postDelayed(bokehFrameTimeout, 8000)
    }

    override fun onCancelBokehChecks() {
        bokehCheckCancelled = true
        if (pendingBokehChecks.getAndSet(null) != null) {
            ++bokehCheckGeneration
            main.removeCallbacks(bokehFrameTimeout)
            update { it.copy(bokehChecksRunning = false, bokehCheckStatus = "Checks cancelled") }
        } else update { it.copy(bokehCheckStatus = "Cancelling after current native inference…") }
    }

    private fun abandonBokehChecks() {
        ++bokehCheckGeneration
        bokehCheckCancelled = true
        pendingBokehChecks.set(null)
        main.removeCallbacks(bokehFrameTimeout)
        update { it.copy(bokehChecksRunning = false, bokehCheckStatus = if (it.bokehChecksRunning) "Checks interrupted; run again in Photo" else it.bokehCheckStatus) }
    }

    override fun onCopyBokehChecks() {
        val text = buildString {
            append("NotiCam bokeh runtime checks\n${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
            append("${state.bokehCheckStatus}\nRendered preview now: ${state.previewMeasuredFps.toInt()} fps (not a sustained benchmark)\n")
            state.bokehCheckResults.forEach { append("\n${it.model.label}\n${it.summary}\n") }
            append("\nSame captured frame; one warmup + 5 samples; common input copy/RGB excluded.\nNo image-quality or sustained-FPS score. No images uploaded.\n")
        }
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NotiCam model checks", text))
        Toast.makeText(activity, "Model report copied", Toast.LENGTH_SHORT).show()
    }

    /** Copy only bounded samples on the reader; all model resources belong to the worker. */
    private fun dispatchBokeh(image: android.media.Image) {
        val request = pendingBokehChecks.get()
        val checks = request?.models
        val current = state
        bokehLatestSensorTimestamp = image.timestamp
        val now = SystemClock.elapsedRealtime()
        val live = current.bokehActive && !current.bokehChecksRunning && current.bokehModelError == null
        if (checks == null && (!live || now - lastMatteDispatchMs < 83L)) return
        if (!isMatteWorkerBusy.compareAndSet(false, true)) {
            if (live) native.notifyMatteDrop()
            return
        }
        val generation = frameGeneration
        val modelGeneration = bokehModelGeneration
        val model = selectedBokehModel
        val checkGeneration = request?.token ?: bokehCheckGeneration
        try {
            val input = BokehInput.copy(image) // No Image/plane/AHB escapes this call.
            lastMatteDispatchMs = now
            if (request != null) {
                // Do not consume a newer request after a cancel/restart during copying.
                if (!pendingBokehChecks.compareAndSet(request, null) || request.token != bokehCheckGeneration) {
                    isMatteWorkerBusy.set(false)
                    return
                }
                main.removeCallbacks(bokehFrameTimeout)
            }
            matteExecutor.execute {
                try {
                    if (disposed || !foreground || generation != frameGeneration ||
                        (checks == null && modelGeneration != bokehModelGeneration)) return@execute
                    val frame = input.rgb()
                    if (checks != null) {
                        val cancelled = { disposed || !foreground || checkGeneration != bokehCheckGeneration ||
                            bokehCheckCancelled || generation != frameGeneration || bokehThermal >= PowerManager.THERMAL_STATUS_SEVERE }
                        try {
                            bokehProcessor.checks(frame, checks, bokehThermal, bokehSensorFps, cancelled,
                                progress = { message -> post { if (checkGeneration == bokehCheckGeneration)
                                    update { it.copy(bokehCheckStatus = message) } } },
                                result = { result -> post { if (checkGeneration == bokehCheckGeneration)
                                    update { it.copy(bokehCheckResults = it.bokehCheckResults + result) } } })
                            post { if (checkGeneration == bokehCheckGeneration) update { it.copy(
                                bokehCheckStatus = "Complete · same frame ${frame.timestampNs} ns · runtime checks, not a quality score") } }
                        } catch (_: java.util.concurrent.CancellationException) {
                            post { if (checkGeneration == bokehCheckGeneration) update { it.copy(bokehCheckStatus = "Checks cancelled/interrupted; completed results retained") } }
                        }
                    } else {
                        val start = SystemClock.elapsedRealtimeNanos()
                        val (mask, description) = bokehProcessor.process(frame, model, generation, bokehThermal, bokehSensorFps)
                        val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        post {
                            if (generation == frameGeneration && modelGeneration == bokehModelGeneration && foreground &&
                                state.bokehActive && !state.bokehChecksRunning) {
                                val fresh = kotlin.math.abs(bokehLatestSensorTimestamp - frame.timestampNs) <= FrameData.MATTE_MAX_AGE_NS
                                latestMatte = mask
                                matteTimestampNs = frame.timestampNs
                                hasRealMatte = fresh
                                update { it.copy(bokehModelReady = fresh, bokehModelError = null,
                                    status = if (it.status.startsWith("Bokeh model failed")) "Ready" else it.status,
                                    bokehModelStatus = String.format(Locale.US, "%s · last cycle %.1f ms%s", description, ms,
                                        if (fresh) "" else " · stale matte; try a lighter model")) }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Selected bokeh engine failed", t)
                    post {
                        if (modelGeneration == bokehModelGeneration && generation == frameGeneration) {
                            hasRealMatte = false
                            update { it.copy(bokehModelReady = false, bokehModelError = t.message?.take(200) ?: t.javaClass.simpleName,
                                bokehModelStatus = "Model failed; select again or run checks to retry",
                                status = "Bokeh model failed — open Settings to retry or change model") }
                        }
                        if (checks != null && checkGeneration == bokehCheckGeneration)
                            update { it.copy(bokehCheckStatus = "Check failed: ${t.message?.take(200)}") }
                    }
                } finally {
                    isMatteWorkerBusy.set(false)
                    if (checks != null) post { if (checkGeneration == bokehCheckGeneration) {
                        hasRealMatte = false
                        update { it.copy(bokehChecksRunning = false, bokehModelReady = false) }
                    } }
                }
            }
        } catch (t: Throwable) {
            isMatteWorkerBusy.set(false)
            post { if (checks != null && checkGeneration == bokehCheckGeneration) {
                pendingBokehChecks.set(null)
                main.removeCallbacks(bokehFrameTimeout)
                update { it.copy(bokehChecksRunning = false, bokehCheckStatus = "Could not copy camera frame: ${t.message?.take(160)}") }
            } }
        }
    }

    override fun onSettingsToggle() {
        update { it.copy(settingsOpen = !it.settingsOpen) }
        haptic()
    }

    override fun onModeSelected(mode: CaptureMode) {
        if (state.previewTransitioning || state.bokehChecksRunning) return
        if (mode == state.mode || state.captureBusy || state.videoRecording || state.videoStarting) return
        invalidateAuxiliaryFrames()
        update { it.copy(mode = mode, bokehEnabled = false, settingsOpen = false,
            resolution = Resolution.Standard, manualFocus = false, aeAfLocked = false, status = "Switching mode…") }
        rebuildStillSession("Opening ${mode.label}…")
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
        applyZoom((queuedZoom ?: state.zoom) * scaleFactor, force = false)
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
        applyZoom((queuedZoom ?: state.zoom) * exp(deltaPx / 180f), force = false)
        showDial()
    }

    override fun onZoomDragEnd() {
        // Extend the read-out so the number the drag landed on can be read, but
        // never summon one on the way out.
        if (state.zoomDialVisible) armDialHide()
    }

    private fun invalidateAuxiliaryFrames() {
        hasRealMatte = false
        update { it.copy(bokehModelReady = false) }
        frameGeneration++
        synchronized(depthFrames) { depthFrames.clear() }
        latestDepth = null
    }

    private fun applyZoom(requested: Float, force: Boolean) {
        if (!requested.isFinite() || state.bokehChecksRunning || state.captureBusy || state.videoRecording || state.videoStarting || !foreground || disposed) return
        val zoom = requested.coerceIn(DeviceProfile.ZOOM_STOPS.first(), DeviceProfile.ZOOM_STOPS.last())
        if (zoomHandoffPending) { queuedZoom = zoom; return }
        if (!state.sessionReady) return
        if (!force && kotlin.math.abs(zoom - state.zoom) < .005f) return
        val oldZoom = state.zoom
        val lens = CameraZoom.lensForZoom(zoom)
        val allow50MpZoom = state.resolution == Resolution.High && ((lens == "0" && kotlin.math.abs(zoom - 1f) < .01f) || (lens == "3" && kotlin.math.abs(zoom - 2f) < .01f))
        if (lens != currentLens || (state.resolution == Resolution.High && !allow50MpZoom)) {
            beginZoomHandoff(oldZoom, zoom) { target ->
                val targetLens = CameraZoom.lensForZoom(target)
                val allowTarget50Mp = state.resolution == Resolution.High && ((targetLens == "0" && kotlin.math.abs(target - 1f) < .01f) || (targetLens == "3" && kotlin.math.abs(target - 2f) < .01f))
                if (state.resolution == Resolution.High && !allowTarget50Mp) {
                    currentLens = targetLens
                    onTele = targetLens == "3"
                    lensSwitching = true
                    update { it.copy(resolution = Resolution.Standard) }
                    rebuildStillSession("Zoom uses 12 MP. Switching…")
                } else if (targetLens != currentLens) selectLens(targetLens)
                else {
                    // A reversal before camera closure no longer strands sessionReady=false.
                    try { service?.setZoom(target) } catch (e: RemoteException) { Log.w(TAG, "Retarget zoom", e) }
                    update { it.copy(sessionReady = true, zoomBlendReady = true, zoomBlendPending = false) }
                    if (state.zoomBlendImage == null) onZoomBlendFinished(zoomHandoffToken)
                }
                lastSentZoom = target
            }
            return
        }
        update { it.copy(zoom = zoom) }
        if (force || kotlin.math.abs(zoom - lastSentZoom) > .005f) {
            lastSentZoom = zoom
            try { service?.setZoom(zoom) } catch (e: RemoteException) { Log.w(TAG, "setZoom failed", e) }
        }
    }

    private fun selectLens(lens: String) {
        if (state.captureBusy || state.videoRecording || state.videoStarting || lens == currentLens) return
        currentLens = lens
        onTele = lens == "3"
        lensSwitching = true
        update { it.copy(aeAfLocked = false, manualFocus = false) }
        val keep50Mp = state.resolution == Resolution.High && (lens == "0" || lens == "3")
        if (!keep50Mp && state.resolution == Resolution.High || state.bokehEnabled) {
            update { it.copy(resolution = Resolution.Standard, bokehEnabled = false, aeAfLocked = false, manualFocus = false) }
        }
        invalidateAuxiliaryFrames()
        if (onTele) native.setCameraOpticalParams(11.12f, 5.80f, 2.40f)
        else native.setCameraOpticalParams(5.56f, 8.16f, 1.88f)
        // Recreate the still stream for the selected sensor. 2× is optical on
        // camera 3, not a 2× SCALER_CROP_REGION on the main camera.
        rebuildStillSession("Switching lens…")
    }

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
        if (state.previewTransitioning || state.bokehChecksRunning) return
        if (state.mode != CaptureMode.Photo || state.captureBusy || !state.sessionReady) return
        val enabled = !state.bokehEnabled
        if (!enabled) matteExecutor.execute { bokehProcessor.close() }
        if (enabled) { currentLens = "0"; onTele = false; lastSentZoom = 1f }
        update { it.copy(bokehEnabled = enabled, resolution = Resolution.Standard, aeAfLocked = false, manualFocus = false,
            zoom = if (enabled) 1f else it.zoom) }
        rebuildStillSession(if (enabled) "Opening portrait processing…" else "Opening native JPEG capture…")
        haptic()
    }

    override fun onResolutionToggle() {
        if (state.previewTransitioning || state.bokehChecksRunning) return
        if (state.mode == CaptureMode.Video) {
            if (state.videoRecording || state.videoStarting) return
            val nextFps = if (state.videoTargetFps == 30) 60 else 30
            update { it.copy(videoTargetFps = nextFps, status = if (nextFps == 60) "Video: 4K 60 FPS (Overclocked)" else "Video: 4K 30 FPS") }
            haptic()
            startStereoSession()
            return
        }
        if (state.mode != CaptureMode.Photo || state.captureBusy || !state.sessionReady) return
        if (state.raw) {
            update { it.copy(raw = false) }
            rebuildStillSession("Returning to 12 MP JPEG…")
            haptic()
            return
        }
        if (state.resolution == Resolution.High) {
            update { it.copy(resolution = Resolution.Standard) }
            rebuildStillSession("Switching to 12 MP…")
        } else if (!state.highResolutionChecked) {
            refreshHighResolutionCapability()
            update { it.copy(status = "Checking 50 MP paths…") }
        } else if (!state.supportsHighResolution) {
            refreshHighResolutionCapability()
            Toast.makeText(activity, "50 MP is not supported on this lens.", Toast.LENGTH_SHORT).show()
        } else {
            // Full-resolution remosaic is supported on 1x ("0") and 2x telephoto ("3").
            val targetLens = if (currentLens == "3") "3" else "0"
            val targetZoom = if (targetLens == "3") 2f else 1f
            currentLens = targetLens
            onTele = targetLens == "3"
            lastSentZoom = targetZoom
            update { it.copy(resolution = Resolution.High, zoom = targetZoom, bokehEnabled = false, raw = false) }
            try { service?.setZoom(targetZoom) } catch (e: RemoteException) { Log.w(TAG, "50 MP zoom reset", e) }
            rebuildStillSession("Opening native 50 MP capture…")
        }
        haptic()
    }

    private fun createStillReader(): ImageReader {
        val raw = state.raw
        val high = state.resolution == Resolution.High && !raw
        val bokeh = state.bokehActive && !raw
        val advertised = if (!high && !bokeh && !raw) try { service?.getStandardJpegSize(currentLens) } catch (e: Exception) { null } else null
        if (!high && !bokeh && !raw && service != null) check(advertised?.size == 2) { "This lens has no advertised native JPEG output" }
        val width = when {
            raw -> if (currentLens == "3") 4096 else 4080
            high -> if (currentLens == "3") 8192 else (state.highResolutionWidth.takeIf { it > 0 } ?: 8160)
            bokeh -> DeviceProfile.STILL_WIDTH
            else -> advertised?.getOrNull(0) ?: DeviceProfile.STILL_WIDTH
        }
        val height = when {
            raw -> 3072
            high -> if (currentLens == "3") 6144 else (state.highResolutionHeight.takeIf { it > 0 } ?: 6144)
            bokeh -> DeviceProfile.STILL_HEIGHT
            else -> advertised?.getOrNull(1) ?: DeviceProfile.STILL_HEIGHT
        }
        check(!high || CaptureOutput.is50MpSize(width, height)) { "50 MP was not advertised by this camera" }
        update { it.copy(photoWidth = width, photoHeight = height) }
        val reader = if (raw) {
            ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, 2)
        } else if (!bokeh) ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
        else ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        return reader.also { it.setOnImageAvailableListener(::onStillImage, stillHandler) }
    }

    private fun rebuildStillSession(message: String) {
        val svc = service ?: return
        val generation = ++sessionToken
        pendingRebuildToken = generation
        main.removeCallbacks(previewFrameTimeout)
        update { it.copy(sessionReady = false, status = message) }
        invalidateAuxiliaryFrames()
        io.execute {
            try {
                svc.closeSession()
                post {
                    if (pendingRebuildToken == generation) pendingRebuildToken = null
                    if (generation == sessionToken && service === svc && foreground && !state.galleryOpen) {
                        try {
                            val old = stillReader
                            old?.setOnImageAvailableListener(null, null)
                            stillReader = createStillReader()
                            stillHandler?.post { old?.close() }
                            startStereoSession()
                        } catch (e: Exception) {
                            cancelZoomHandoff()
                            update { it.copy(status = "Could not configure capture stream: ${e.message}", sessionReady = false) }
                        }
                    }
                }
            } catch (e: Exception) {
                post {
                    if (pendingRebuildToken == generation) pendingRebuildToken = null
                    if (generation == sessionToken) {
                        cancelZoomHandoff()
                        update { it.copy(status = "Could not close camera stream", sessionReady = false) }
                    }
                }
            }
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
        if (state.previewTransitioning || state.captureBusy || !state.sessionReady) return
        val newRaw = !state.raw
        if (newRaw) {
            update { it.copy(raw = true, resolution = Resolution.Standard, bokehEnabled = false) }
            rebuildStillSession("Enabling RAW (DNG) capture…")
        } else {
            update { it.copy(raw = false) }
            rebuildStillSession("Returning to standard JPEG…")
        }
        haptic()
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
        if (state.mode == CaptureMode.Video) {
            if (state.videoRecording) stopVideo(save = true, restore = true)
            else if (state.canUseShutter) startVideo()
            return
        }
        if (!state.canTakePhoto) return
        Log.i(TAG, "onShutterClick called, sessionReady=${state.sessionReady}")
        if (state.timerSeconds > 0) {
            startTimerCapture(state.timerSeconds)
        } else {
            triggerCapture(false, true)
        }
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        if (!foreground || disposed || state.mode != CaptureMode.Video) return
        if (granted) startVideo()
        else {
            Toast.makeText(activity, "Microphone denied. Tap Record again to record without audio.", Toast.LENGTH_LONG).show()
            update { it.copy(status = "Microphone off • tap Record for silent video") }
        }
    }

    private fun startVideo() {
        if (!state.canUseShutter || state.videoRecording || disposed || !foreground) return
        val audio = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!audio && !microphonePermissionAsked) {
            microphonePermissionAsked = true
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 201)
            return
        }
        val svc = service ?: return
        val lens = currentLens
        val generation = ++videoGeneration
        update { it.copy(videoStarting = true, captureBusy = true, sessionReady = false,
            settingsOpen = false, videoHasAudio = audio, status = "Preparing video…") }
        main.postDelayed(videoStartTimeout, 15_000)
        io.execute {
            try {
                val size = svc.getVideoSize(lens)
                check(size.size == 3) { "No native-aspect 30 fps recorder + AVC encoder stream on this lens. No 16:9 substitute." }
                val capture = VideoCapture(activity.applicationContext)
                videoCapture = capture
                capture.prepare(size[0], size[1], audio, size[2], state.videoTargetFps,
                    onFailure = { post { if (generation == videoGeneration) stopVideo(false, true) } },
                    onLimit = { post { if (generation == videoGeneration) stopVideo(true, true) } })
                recordingSurface = capture.surface
                post { if (generation == videoGeneration && foreground) {
                    update { it.copy(videoWidth = size[0], videoHeight = size[1]) }
                    startStereoSession()
                } }
            } catch (e: Exception) {
                Log.e(TAG, "Video prepare failed", e)
                videoCapture?.finish(false)
                videoCapture = null
                recordingSurface = null
                post {
                    if (generation == videoGeneration) {
                        main.removeCallbacks(videoStartTimeout)
                        update { it.copy(videoStarting = false, captureBusy = false, status = "Could not prepare video: ${e.message}") }
                        if (foreground) startStereoSession()
                    }
                }
            }
        }
    }

    private fun stopVideo(save: Boolean, restore: Boolean) {
        if (!state.videoStarting && !state.videoRecording) return
        val generation = ++videoGeneration
        ++sessionToken // Late errors from the closing recorder session are irrelevant.
        main.removeCallbacks(videoTick)
        main.removeCallbacks(videoStartTimeout)
        update { it.copy(videoRecording = false, videoStarting = false, captureBusy = true,
            sessionReady = false, status = "Finishing video…") }
        try { service?.closeSession() } catch (_: Exception) { }
        io.execute {
            recordingSurface = null
            val destination = videoCapture?.finish(save)
            videoCapture = null
            val thumbnail = destination?.let { uri ->
                try { activity.contentResolver.loadThumbnail(uri, Size(168, 168), null).asImageBitmap() }
                catch (_: Exception) { null }
            }
            post {
                if (generation == videoGeneration) {
                    thumbnail?.let { onThumbnailReady(it) }
                    update { it.copy(captureBusy = false, status = if (destination != null) "Video saved" else "Video not saved — empty or interrupted clip") }
                    if (restore && foreground) startStereoSession()
                }
            }
        }
    }

    override fun onGalleryOpen() {
        if (state.previewTransitioning || state.bokehChecksRunning) return
        main.removeCallbacks(previewFrameTimeout)
        if (state.captureBusy || state.videoRecording || state.videoStarting) return
        ++sessionToken
        pendingRebuildToken = null
        try { service?.closeSession() } catch (_: Exception) { }
        engineReady = false
        invalidateAuxiliaryFrames()
        native.destroyEngine()
        update { it.copy(galleryOpen = true, settingsOpen = false, sessionReady = false) }
    }

    override fun onGalleryClose() {
        update { it.copy(galleryOpen = false) }
        if (foreground) {
            surfaceHolder?.takeIf { it.surface.isValid }?.let { initializePreviewEngine(it) }
            rebuildStillSession("Returning to native capture…")
        }
    }

    override fun onCameraReport() {
        val svc = service ?: return
        update { it.copy(status = "Inspecting camera capabilities…") }
        io.execute {
            val report = try { svc.getCameraDiagnostics() } catch (e: Exception) { "Camera report failed: ${e.message}" }
            post {
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "NotiCam camera capability report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }, "Share camera capability report"))
            }
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
        bokehThermal = status
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
