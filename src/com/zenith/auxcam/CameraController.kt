package com.zenith.auxcam

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraManager
import android.media.ImageReader
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
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Native JNI entry points, implemented by [MainActivity]. */
interface NativeBridge {
    fun initEngine(surface: Surface, width: Int, height: Int): Boolean
    fun renderFrame(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
    ): Boolean

    fun setCameraOpticalParams(focalMm: Float, sensorWidthMm: Float, baseFStop: Float)

    fun processStill(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
        outPath: String,
        timestampsOut: LongArray,
    ): Boolean

    fun setThermalThrottled(throttled: Boolean)
    fun destroyEngine()
}

/** Everything the Compose chrome can ask the camera to do. */
interface CameraActions {
    fun onFlashClick()
    fun onProToggle()
    fun onModeSelected(mode: CaptureMode)
    fun onZoomSelected(zoom: Float)
    fun onZoomGesture(scaleFactor: Float)

    /** @param fStop real f-number, e.g. 2.36f — continuous, not an index. */
    fun onApertureChanged(fStop: Float)

    /** @param ev exposure compensation in EV, continuous (sent as 1/3 steps). */
    fun onEvChanged(ev: Float)
    fun onGridToggle()
    fun onRawToggle()
    fun onTimerCycle()
    fun onShutterClick()
    fun onSwitchLens()
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
    private inline fun update(block: (CameraUiState) -> CameraUiState) {
        _uiState.value = block(_uiState.value)
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
    private var surfaceHolder: SurfaceHolder? = null
    private val stillCaptureInProgress = AtomicBoolean(false)

    @Volatile
    private var latestDepth: ByteArray? = null

    private var shutterClickNs = 0L
    private var lensSwitching = false

    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var captureReceiver: BroadcastReceiver? = null

    private var lastSentZoom = 1f
    private var lastSentEvSteps = 0
    private val physicalLenses = mutableListOf<String>()
    private var currentLens = "0"

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
        service = null
        update { it.copy(status = "Reconnecting…", sessionReady = false) }
    }

    // ═══════════════════════════ LIFECYCLE ══════════════════════════════════

    fun start() {
        if (started) return
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
                update { it.copy(mode = if (hq) CaptureMode.Pro else it.mode) }
                triggerCapture(hq, zsl)
            }
        }
        captureReceiver = receiver
        val filter = IntentFilter("com.zenith.auxcam.CAPTURE_STILL")
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
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

    fun dispose() {
        main.removeCallbacksAndMessages(null)
        io.shutdown()

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

        colorReader?.close()
        depthReader?.close()
        stillReader?.close()
        colorReader = null
        depthReader = null
        stillReader = null

        try {
            service?.closeSession()
        } catch (e: RemoteException) {
            Log.w(TAG, "closeSession failed")
        }
        service = null

        stopReaderThread()

        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)

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
        val ok = native.initEngine(holder.surface, width, height)
        Log.i(TAG, "nativeInitEngine -> $ok")
        if (service != null) startStereoSession()
    }

    override fun onPreviewSurfaceChanged(holder: SurfaceHolder) {
        // The engine detects the new surface extent itself and rebuilds its
        // swapchain (see VulkanPipeline::recreateSwapchain), so nothing to do
        // here beyond remembering the holder.
        surfaceHolder = holder
    }

    override fun onPreviewSurfaceDestroyed(holder: SurfaceHolder) {
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
        update { it.copy(status = "Binding service…") }
        val args = Shizuku.UserServiceArgs(
            ComponentName(activity.packageName, CameraUserService::class.java.name),
        )
            .daemon(false)
            .version(2)
            .processNameSuffix("auxcam_privileged")
        Shizuku.bindUserService(args, this)
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = binder?.let { IAuxCamService.Stub.asInterface(it) }
        update { it.copy(status = "Opening session…") }
        startStereoSession()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        update { it.copy(status = "Disconnected", sessionReady = false) }
    }

    // ═══════════════════════════ SESSION ═════════════════════════════════════

    private fun setupImageReaders() {
        val handler = readerHandler ?: return

        colorReader = ImageReader.newInstance(
            DeviceProfile.COLOR_WIDTH,
            DeviceProfile.COLOR_HEIGHT,
            ImageFormat.YUV_420_888,
            3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
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

        stillReader = ImageReader.newInstance(
            DeviceProfile.STILL_WIDTH,
            DeviceProfile.STILL_HEIGHT,
            ImageFormat.YUV_420_888,
            2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        ).also { reader ->
            reader.setOnImageAvailableListener({ r -> onStillImage(r) }, handler)
        }
    }

    private fun startStereoSession() {
        val svc = service ?: return
        val holder = surfaceHolder ?: return
        val color = colorReader ?: return
        val depth = depthReader ?: return
        val still = stillReader ?: return

        // The preview surface must exist before the engine is initialised.
        if (!holder.surface.isValid) return

        try {
            svc.setCallback(object : IAuxCamCallback.Stub() {
                override fun onSessionReady() { post { this@CameraController.onSessionReady() } }
                override fun onSessionError(msg: String?) { post { this@CameraController.onSessionError(msg) } }
                override fun onStillCaptured() { post { this@CameraController.onStillCaptured() } }
                override fun onStats(colorFps: Float, depthFps: Float, dt: Float) {
                    post { this@CameraController.onStats(colorFps, depthFps, dt) }
                }
            })

            val ok = svc.openStereoSession(
                color.surface, DeviceProfile.COLOR_WIDTH, DeviceProfile.COLOR_HEIGHT,
                depth.surface, DeviceProfile.DEPTH_WIDTH, DeviceProfile.DEPTH_HEIGHT,
                still.surface, DeviceProfile.STILL_WIDTH, DeviceProfile.STILL_HEIGHT,
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
        main.post(block)
    }

    private fun onSessionReady() {
        lensSwitching = false
        update {
            it.copy(
                status = "Ready",
                sessionReady = true,
                lensIndex = physicalLenses.indexOf(currentLens).coerceAtLeast(0),
                lensCount = physicalLenses.size.coerceAtLeast(1),
            )
        }
    }

    private fun onSessionError(msg: String?) {
        update { it.copy(status = "Session error", sessionReady = false) }
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
        update { it.copy(status = "Saved to Photos") }
    }

    private fun onStats(colorFps: Float, depthFps: Float, dt: Float) {
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

    private fun onColorImage(reader: ImageReader) {
        if (stillCaptureInProgress.get()) {
            val dropped = reader.acquireLatestImage()
            dropped?.close()
            return
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val image = reader.acquireLatestImage() ?: return
        try {
            val buffer = image.hardwareBuffer
            if (buffer != null) {
                try {
                    val targetFarFieldPx = 15.0f * (1.8f / state.fStop)
                    native.renderFrame(
                        buffer,
                        latestDepth,
                        DeviceProfile.DEPTH_WIDTH,
                        DeviceProfile.DEPTH_HEIGHT,
                        state.fStop,
                        targetFarFieldPx,
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "renderFrame error: ${t.message}")
                } finally {
                    buffer.close()
                }
            }
        } finally {
            image.close()
        }
        val dtMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        if (dtMs > 60) Log.w(TAG, "slow preview frame: ${dtMs}ms")
    }

    private fun onDepthImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            latestDepth = bytes
        } catch (t: Throwable) {
            Log.e(TAG, "depth read failed: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun onStillImage(reader: ImageReader) {
        val t2 = SystemClock.elapsedRealtimeNanos()
        val image = reader.acquireLatestImage() ?: return
        var outPath: String? = null
        try {
            val t3 = SystemClock.elapsedRealtimeNanos()
            val buffer = image.hardwareBuffer
            var gpuElapsedNs = 0L
            var t5 = t3

            if (buffer != null) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dir = activity.getExternalFilesDir("bokeh_raw") ?: activity.filesDir
                if (!dir.exists()) dir.mkdirs()
                outPath = File(dir, "AUXCAM_$stamp.rgba").absolutePath

                if (state.raw) dumpRawPlanes(image, dir, stamp)

                val targetFarFieldPx = 15.0f * (1.8f / state.fStop)
                val timestamps = LongArray(3)
                native.processStill(
                    buffer,
                    latestDepth,
                    DeviceProfile.DEPTH_WIDTH,
                    DeviceProfile.DEPTH_HEIGHT,
                    state.fStop,
                    targetFarFieldPx,
                    outPath,
                    timestamps,
                )
                val t4 = SystemClock.elapsedRealtimeNanos()
                gpuElapsedNs = timestamps[0]
                t5 = t4
                buffer.close()

                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "STILL shutter→yuv=%dms gpu=%dms (fence %dms) aperture=f/%.1f zoom=%.2f path=%s",
                        (t2 - shutterClickNs) / 1_000_000,
                        (t4 - t3) / 1_000_000,
                        gpuElapsedNs / 1_000_000,
                        state.fStop,
                        state.zoom,
                        outPath,
                    ),
                )
                Log.i(TAG, "STILL total shutter→file=${(t5 - shutterClickNs) / 1_000_000}ms")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "still processing failed", t)
        } finally {
            stillCaptureInProgress.set(false)
            image.close()
        }

        outPath?.let { path ->
            io.execute { buildThumbnail(path)?.let { bitmap -> post { onThumbnailReady(bitmap) } } }
        }
    }

    private fun dumpRawPlanes(image: android.media.Image, dir: File, stamp: String) {
        try {
            val yBuffer = image.planes[0].buffer
            val yBytes = ByteArray(yBuffer.remaining())
            yBuffer.get(yBytes)
            File(dir, "AUXCAM_${stamp}_raw_y.bin").writeBytes(yBytes)
            latestDepth?.let { File(dir, "AUXCAM_${stamp}_depth.y8").writeBytes(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "raw dump note: ${t.message}")
        }
    }

    /**
     * Samples the engine's raw RGBA dump straight off disk. A full-res 4K frame is
     * ~50 MB, so we seek row by row and only ever keep a 168 × 126 thumbnail —
     * the heap never sees the full bitmap.
     */
    private fun buildThumbnail(rgbaPath: String): ImageBitmap? {
        var raf: RandomAccessFile? = null
        return try {
            val file = File(rgbaPath)
            val srcW = DeviceProfile.STILL_WIDTH
            val srcH = DeviceProfile.STILL_HEIGHT
            if (!file.exists() || file.length() < srcW.toLong() * srcH * 2L) return null

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

    private fun triggerCapture(highQuality: Boolean, enableZsl: Boolean) {
        val svc = service ?: run {
            Log.e(TAG, "triggerCapture failed: service is null")
            update { it.copy(status = "Camera not ready") }
            return
        }
        Log.i(TAG, "triggerCapture: highQuality=$highQuality, enableZsl=$enableZsl")
        stillCaptureInProgress.set(true)
        main.postDelayed({ stillCaptureInProgress.set(false) }, 2500L)
        shutterClickNs = SystemClock.elapsedRealtimeNanos()
        update {
            it.copy(
                capturePulse = it.capturePulse + 1,
                status = "Capturing…",
            )
        }
        try {
            svc.triggerStillWithZsl(shutterClickNs, highQuality, enableZsl)
        } catch (e: RemoteException) {
            stillCaptureInProgress.set(false)
            Log.e(TAG, "triggerStill failed", e)
        }
    }

    private fun startTimerCapture(remaining: Int) {
        if (remaining <= 0) {
            triggerCapture(state.mode.highQualityNr, true)
            return
        }
        update { it.copy(status = "${remaining}s") }
        haptic()
        main.postDelayed({ startTimerCapture(remaining - 1) }, 1000L)
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

    override fun onProToggle() {
        update { it.copy(proSheetOpen = !it.proSheetOpen) }
        haptic()
    }

    override fun onModeSelected(mode: CaptureMode) {
        if (mode == state.mode) return
        update { it.copy(mode = mode) }
        haptic()
    }

    override fun onZoomSelected(zoom: Float) {
        applyZoom(zoom, force = true)
        haptic()
    }

    override fun onZoomGesture(scaleFactor: Float) {
        if (scaleFactor == 0f) return
        applyZoom(state.zoom * scaleFactor, force = false, showWheel = true)
    }

    private fun applyZoom(requested: Float, force: Boolean, showWheel: Boolean = false) {
        val clamped = requested.coerceIn(DeviceProfile.ZOOM_STOPS.first(), DeviceProfile.ZOOM_STOPS.last())
        if (!force && kotlin.math.abs(clamped - state.zoom) < 0.005f) return

        // Only a pinch summons the radial wheel. Tapping a chip keeps the chips
        // on screen — they must not swap themselves out for the wheel.
        update {
            it.copy(
                zoom = clamped,
                zoomDialVisible = if (showWheel) true else it.zoomDialVisible,
            )
        }
        if (showWheel) {
            main.removeCallbacks(hideDial)
            main.postDelayed(hideDial, 1600L)
        }

        // Pinch produces dozens of events per gesture — only forward real changes.
        if (force || kotlin.math.abs(clamped - lastSentZoom) > 0.02f) {
            lastSentZoom = clamped
            try {
                service?.setZoom(clamped)
            } catch (e: RemoteException) {
                Log.w(TAG, "setZoom failed")
            }
        }
    }

    private val hideDial = Runnable {
        update { it.copy(zoomDialVisible = false) }
    }

    override fun onApertureChanged(fStop: Float) {
        val clamped = fStop.coerceIn(FSTOPS.first(), FSTOPS.last())
        if (kotlin.math.abs(clamped - state.fStop) < 0.001f) return
        update { it.copy(fStop = clamped) }
    }

    override fun onEvChanged(ev: Float) {
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

    override fun onGridToggle() {
        update { it.copy(grid = !it.grid) }
        haptic()
    }

    override fun onRawToggle() {
        update { it.copy(raw = !it.raw) }
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
        Log.i(TAG, "onShutterClick called, sessionReady=${state.sessionReady}")
        if (state.timerSeconds > 0) {
            startTimerCapture(state.timerSeconds)
        } else {
            triggerCapture(state.mode.highQualityNr, true)
        }
    }

    override fun onGalleryOpen() {
        // The engine writes raw RGBA dumps into app storage (they are 50 MB and not
        // viewable), so this opens the system gallery on the user's real photos.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (t: ActivityNotFoundException) {
            Log.w(TAG, "no gallery app installed: ${t.message}")
            Toast.makeText(activity, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSwitchLens() {
        if (physicalLenses.size < 2) return
        val nextIndex = (physicalLenses.indexOf(currentLens) + 1).mod(physicalLenses.size)
        currentLens = physicalLenses[nextIndex]
        lensSwitching = true
        lastSentZoom = 1f
        // Optical constants: 1x Wide (5.56mm, 8.16mm, f/1.88) vs 2x Tele (11.12mm, 5.80mm, f/2.40)
        if (nextIndex == 1) {
            native.setCameraOpticalParams(11.12f, 5.80f, 2.40f)
        } else {
            native.setCameraOpticalParams(5.56f, 8.16f, 1.88f)
        }
        update {
            it.copy(
                zoom = 1f,
                lensIndex = nextIndex,
                status = "Lens ${nextIndex + 1}/${physicalLenses.size}",
            )
        }
        haptic()
        try {
            service?.setPhysicalLens(currentLens)
        } catch (e: RemoteException) {
            Log.w(TAG, "setPhysicalLens failed: fallback to 1x")
            lensSwitching = false
            currentLens = physicalLenses[0]
            native.setCameraOpticalParams(5.56f, 8.16f, 1.88f)
            update {
                it.copy(
                    zoom = 1f,
                    lensIndex = 0,
                    status = "1x (Wide)",
                )
            }
        }
    }

    /**
     * The shader maps upright screen space onto sensor space (see composite.comp),
     * so the tap has to be expressed in the same space before it reaches the HAL.
     */
    override fun onFocusAt(normalisedX: Float, normalisedY: Float) {
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

        val defaultIndex = physicalLenses.indexOf("0")
        currentLens = if (defaultIndex >= 0) "0" else physicalLenses.firstOrNull() ?: "0"
        update {
            it.copy(
                lensCount = physicalLenses.size.coerceAtLeast(1),
                lensIndex = defaultIndex.coerceAtLeast(0),
            )
        }
        Log.i(TAG, "physical lenses: $physicalLenses (current=$currentLens)")
    }
}
