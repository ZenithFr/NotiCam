package com.zenith.auxcam

import android.content.Context
import android.media.Image
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class TensorDetails(
    val dtype: DataType,
    val shape: IntArray,
    val quantizationParams: Tensor.QuantizationParams
)

operator fun Tensor.QuantizationParams.component1(): Float = scale
operator fun Tensor.QuantizationParams.component2(): Int = zeroPoint

private fun Interpreter.getInputDetails(): List<TensorDetails> {
    val list = mutableListOf<TensorDetails>()
    for (i in 0 until inputTensorCount) {
        val t = getInputTensor(i)
        list.add(TensorDetails(t.dataType(), t.shape(), t.quantizationParams()))
    }
    return list
}

private fun Interpreter.getOutputDetails(): List<TensorDetails> {
    val list = mutableListOf<TensorDetails>()
    for (i in 0 until outputTensorCount) {
        val t = getOutputTensor(i)
        list.add(TensorDetails(t.dataType(), t.shape(), t.quantizationParams()))
    }
    return list
}

/**
 * Production dual-tier MediaPipe portrait segmentation engine on mt6878 APU (mtk-mdla_shim).
 *
 * Implements:
 *   - SHA256 verification of packaged INT8 models against project ledger at init.
 *   - Asymmetric quantization reading (in_scale, in_zero, out_scale, out_zero).
 *   - Stretch resizing to [1,144,256,3] and [1,256,256,3] without letterboxing.
 *   - Dequantization to float probability and bilinear upsample of landscape to 256x256.
 *   - Pixel-max combination with dual threshold 0.65 (soft alpha by default).
 *   - 12 Hz rate limiting, 16-bin luma histogram scene-change fast path (>20% shift).
 *   - Spatial/temporal EMA smoothing (alpha=0.25).
 *   - Thermal fallback (DUAL -> FAST landscape alone, thr 0.45) on thermal >= 2 or fps < 28.
 */
class DualMatteSegmenter(private val context: Context) {

    enum class TierMode {
        DUAL, // Landscape + General (pixel-max, thr 0.65)
        FAST  // Landscape alone (thr 0.45, thermal fallback)
    }

    companion object {
        private const val TAG = "DualMatteSegmenter"

        const val EXPECTED_FLOAT_HASH_LANDSCAPE =
            "1c2c4742c36c0e9fefeff2ab36110409eb7fb0c83dcaf3e1ec095f045fed0a53"
        const val EXPECTED_FLOAT_HASH_GENERAL =
            "62fb4bda888231741ae9be98bff2dc1bced55a6e474db5b3ba5cb37e83c1ec07"

        const val EXPECTED_INT8_HASH_LANDSCAPE =
            "d1333fbfd66e880466bec06d3ffa0a57dfb8e247cb69191ca64d8e4471cd46ba"
        const val EXPECTED_INT8_HASH_GENERAL =
            "45a82a06efc98a875e5092723b57ec144c62617bc368f76dfc1fc67e3ea4bb53"

        const val THR_DUAL = 0.65f
        const val THR_FAST = 0.45f
        const val EMA_ALPHA = 0.25f
        const val MIN_INTERVAL_MS = 83L // 12 Hz
        const val FAST_PATH_HIST_DELTA = 0.20f // 20% total variation shift

        // Runtime toggle for A/B comparison (default false = soft alpha)
        @Volatile
        var MATTE_HARD_THRESHOLD: Boolean = false

        // L.0 Diagnostic contrast-stretch toggle (default false for production)
        @Volatile
        var STRETCH_MATTE: Boolean = false

        // L.1 Float stopgap CPU XNNPACK toggle
        @Volatile
        var FORCE_CPU_FLOAT: Boolean = false
    }

    private var interpLandscape: Interpreter? = null
    private var interpGeneral: Interpreter? = null
    private var nnapiDelegateL: NnApiDelegate? = null
    private var nnapiDelegateG: NnApiDelegate? = null

    // Dtypes read dynamically at init
    private var inDtypeL: DataType = DataType.FLOAT32
    private var outDtypeL: DataType = DataType.FLOAT32
    private var inDtypeG: DataType = DataType.FLOAT32
    private var outDtypeG: DataType = DataType.FLOAT32

    // Quantization parameters read dynamically at init (used only for INT8 fallback)
    private var inScaleL = 1.0f
    private var inZeroL = 0
    private var outScaleL = 1.0f
    private var outZeroL = 0

    private var inScaleG = 1.0f
    private var inZeroG = 0
    private var outScaleG = 1.0f
    private var outZeroG = 0

    // Direct ByteBuffers sized dynamically at init
    private var inBufL: ByteBuffer = ByteBuffer.allocateDirect(1 * 144 * 256 * 3 * 4).order(ByteOrder.nativeOrder())
    private var outBufL: ByteBuffer = ByteBuffer.allocateDirect(1 * 144 * 256 * 1 * 4).order(ByteOrder.nativeOrder())
    private var inBufG: ByteBuffer = ByteBuffer.allocateDirect(1 * 256 * 256 * 3 * 4).order(ByteOrder.nativeOrder())
    private var outBufG: ByteBuffer = ByteBuffer.allocateDirect(1 * 256 * 256 * 1 * 4).order(ByteOrder.nativeOrder())

    // Probability buffers
    private val probL = FloatArray(144 * 256)
    private val probG = FloatArray(256 * 256)
    private val probUpsampledL = FloatArray(256 * 256)
    private val probCombined = FloatArray(256 * 256)
    private val emaMatte = FloatArray(256 * 256)
    private var emaInitialized = false

    // Output alpha byte buffer (256x256 R8 in [0..255])
    private val outputAlphaBytes = ByteArray(256 * 256)
    private val alphaLock = Any()
    private var loggedFirstInferenceFailure = false

    @Volatile
    var currentMode: TierMode = TierMode.DUAL
        private set

    private var lastDispatchTimeMs = 0L
    private val prevHistogram = FloatArray(16)
    private var hasPrevHistogram = false

    private val isInitialized = AtomicBoolean(false)

    fun initialize(): Boolean {
        if (isInitialized.get()) return true
        Log.i(TAG, "=== Initializing DualMatteSegmenter ===")

        try {
            // 1. Model Selection & Loading
            // Landscape candidates (prefer float, fallback to int8)
            val landscapeFloatCandidates = listOf(
                "mp_landscape_fixed_float.tflite",
                "models/mp_landscape_fixed_float.tflite"
            )
            val landscapeInt8Candidates = listOf(
                "models/mp_landscape_fixed_int8.tflite",
                "mp_landscape_fixed_int8.tflite"
            )

            var loadedLandscapeBuffer: ByteBuffer? = null
            var loadedLandscapeSha: String? = null
            var isFloatLandscape = false

            for (path in landscapeFloatCandidates) {
                val res = tryLoadAndHash(path)
                if (res != null) {
                    loadedLandscapeBuffer = res.first
                    loadedLandscapeSha = res.second
                    isFloatLandscape = true
                    Log.i(TAG, "Loaded float landscape model candidate: $path")
                    break
                }
            }
            if (loadedLandscapeBuffer == null) {
                for (path in landscapeInt8Candidates) {
                    val res = tryLoadAndHash(path)
                    if (res != null) {
                        loadedLandscapeBuffer = res.first
                        loadedLandscapeSha = res.second
                        isFloatLandscape = false
                        Log.i(TAG, "Fallback to int8 landscape model candidate: $path")
                        break
                    }
                }
            }

            if (loadedLandscapeBuffer == null || loadedLandscapeSha == null) {
                Log.e(TAG, "FATAL: Could not load any landscape model!")
                return false
            }

            Log.i(TAG, "MATTE_LANDSCAPE_ASSET_SHA=$loadedLandscapeSha")

            if (isFloatLandscape) {
                if (!loadedLandscapeSha.equals(EXPECTED_FLOAT_HASH_LANDSCAPE, ignoreCase = true)) {
                    Log.e(
                        TAG,
                        "FATAL: Landscape float model SHA256 mismatch! Expected $EXPECTED_FLOAT_HASH_LANDSCAPE, got $loadedLandscapeSha"
                    )
                    return false
                }
            } else {
                if (!loadedLandscapeSha.equals(EXPECTED_INT8_HASH_LANDSCAPE, ignoreCase = true)) {
                    Log.e(
                        TAG,
                        "FATAL: Landscape int8 model SHA256 mismatch! Expected $EXPECTED_INT8_HASH_LANDSCAPE, got $loadedLandscapeSha"
                    )
                    return false
                }
            }

            // General candidates (prefer float, fallback to int8)
            val generalFloatCandidates = listOf(
                "mp_general_fixed_float.tflite",
                "models/mp_general_fixed_float.tflite"
            )
            val generalInt8Candidates = listOf(
                "models/mp_general_fixed_int8.tflite",
                "mp_general_fixed_int8.tflite"
            )

            var loadedGeneralModel: String? = null
            var loadedGeneralBuffer: ByteBuffer? = null
            var loadedGeneralSha: String? = null
            var isFloatGeneral = false

            for (path in generalFloatCandidates) {
                val res = tryLoadAndHash(path)
                if (res != null) {
                    loadedGeneralModel = path
                    loadedGeneralBuffer = res.first
                    loadedGeneralSha = res.second
                    isFloatGeneral = true
                    Log.i(TAG, "Loaded float general model candidate: $path")
                    break
                }
            }
            if (loadedGeneralBuffer == null) {
                for (path in generalInt8Candidates) {
                    val res = tryLoadAndHash(path)
                    if (res != null) {
                        loadedGeneralModel = path
                        loadedGeneralBuffer = res.first
                        loadedGeneralSha = res.second
                        isFloatGeneral = false
                        Log.i(TAG, "Fallback to int8 general model candidate: $path")
                        break
                    }
                }
            }

            if (loadedGeneralBuffer != null && loadedGeneralSha != null) {
                Log.i(TAG, "MATTE_GENERAL_ASSET_SHA=$loadedGeneralSha")
                if (isFloatGeneral) {
                    if (!loadedGeneralSha.equals(EXPECTED_FLOAT_HASH_GENERAL, ignoreCase = true)) {
                        Log.e(
                            TAG,
                            "FATAL: General float model SHA256 mismatch! Expected $EXPECTED_FLOAT_HASH_GENERAL, got $loadedGeneralSha"
                        )
                        return false
                    }
                } else {
                    if (!loadedGeneralSha.equals(EXPECTED_INT8_HASH_GENERAL, ignoreCase = true)) {
                        Log.e(
                            TAG,
                            "FATAL: General int8 model SHA256 mismatch! Expected $EXPECTED_INT8_HASH_GENERAL, got $loadedGeneralSha"
                        )
                        return false
                    }
                }
            } else {
                Log.w(TAG, "Could not load any general model, degrading to FAST mode")
                currentMode = TierMode.FAST
            }

            // 2. Initialize Interpreters
            val optsL = Interpreter.Options().apply {
                setNumThreads(4)
                if (!FORCE_CPU_FLOAT) {
                    try {
                        val nnOpts = NnApiDelegate.Options().apply {
                            setAcceleratorName("mtk-mdla_shim")
                        }
                        val delegate = NnApiDelegate(nnOpts)
                        nnapiDelegateL = delegate
                        addDelegate(delegate)
                        Log.i(TAG, "Landscape Interpreter configured with mtk-mdla_shim NNAPI delegate")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to create NNAPI delegate for landscape, falling back to CPU", t)
                    }
                } else {
                    setUseXNNPACK(true)
                }
            }

            interpLandscape = Interpreter(loadedLandscapeBuffer, optsL)
            interpLandscape?.allocateTensors()

            if (loadedGeneralBuffer != null) {
                val optsG = Interpreter.Options().apply {
                    setNumThreads(4)
                    if (!FORCE_CPU_FLOAT) {
                        try {
                            val nnOpts = NnApiDelegate.Options().apply {
                                setAcceleratorName("mtk-mdla_shim")
                            }
                            val delegate = NnApiDelegate(nnOpts)
                            nnapiDelegateG = delegate
                            addDelegate(delegate)
                            Log.i(TAG, "General Interpreter configured with mtk-mdla_shim NNAPI delegate")
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to create NNAPI delegate for general, falling back to CPU", t)
                        }
                    } else {
                        setUseXNNPACK(true)
                    }
                }
                interpGeneral = Interpreter(loadedGeneralBuffer, optsG)
                interpGeneral?.allocateTensors()
            }

            // 3. Read Dtype & Quantization Details dynamically
            interpLandscape?.let { interp ->
                val inDetails = interp.getInputDetails()
                val outDetails = interp.getOutputDetails()
                val inDtype = inDetails[0].dtype
                val outDtype = outDetails[0].dtype
                inDtypeL = inDtype
                outDtypeL = outDtype

                val inShape = inDetails[0].shape
                val h = inShape[1]
                val w = inShape[2]

                Log.i(TAG, "MATTE landscape in=$inDtype H=$h W=$w out=$outDtype")

                if (inDtype == DataType.INT8) {
                    val (s0, z0) = inDetails[0].quantizationParams
                    inScaleL = s0
                    inZeroL = z0
                }
                if (outDtype == DataType.INT8) {
                    val (s0, z0) = outDetails[0].quantizationParams
                    outScaleL = s0
                    outZeroL = z0
                }

                inBufL = ByteBuffer.allocateDirect(interp.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                outBufL = ByteBuffer.allocateDirect(interp.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            }

            interpGeneral?.let { interp ->
                val inDetails = interp.getInputDetails()
                val outDetails = interp.getOutputDetails()
                val inDtype = inDetails[0].dtype
                val outDtype = outDetails[0].dtype
                inDtypeG = inDtype
                outDtypeG = outDtype

                val inShape = inDetails[0].shape
                val h = inShape[1]
                val w = inShape[2]

                Log.i(TAG, "MATTE general in=$inDtype H=$h W=$w out=$outDtype")

                if (inDtype == DataType.INT8) {
                    val (s0, z0) = inDetails[0].quantizationParams
                    inScaleG = s0
                    inZeroG = z0
                }
                if (outDtype == DataType.INT8) {
                    val (s0, z0) = outDetails[0].quantizationParams
                    outScaleG = s0
                    outZeroG = z0
                }

                inBufG = ByteBuffer.allocateDirect(interp.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                outBufG = ByteBuffer.allocateDirect(interp.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            }

            // Warmup inferences
            Log.i(TAG, "Warming up interpreters...")
            inBufL.rewind(); outBufL.rewind()
            interpLandscape?.run(inBufL, outBufL)
            if (interpGeneral != null) {
                inBufG.rewind(); outBufG.rewind()
                interpGeneral?.run(inBufG, outBufG)
            }
            Log.i(TAG, "Warmup complete.")

            isInitialized.set(true)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize DualMatteSegmenter", t)
            return false
        }
    }

    private fun tryLoadAndHash(assetPath: String): Pair<ByteBuffer, String>? {
        return try {
            loadAndHashModel(assetPath)
        } catch (t: Throwable) {
            null
        }
    }

    private fun loadAndHashModel(assetPath: String): Pair<ByteBuffer, String> {
        val md = MessageDigest.getInstance("SHA-256")
        // Try file path first, then assets
        val fileCandidates = arrayOf(
            File(context.filesDir, assetPath),
            File(context.filesDir, assetPath.substringAfterLast('/')),
            File("/data/data/com.zenith.auxcam/files/$assetPath")
        )
        for (f in fileCandidates) {
            if (f.exists() && f.canRead()) {
                FileInputStream(f).use { fis ->
                    val fc = fis.channel
                    val buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
                    val bytes = ByteArray(f.length().toInt())
                    buf.get(bytes)
                    buf.rewind()
                    val hash = md.digest(bytes).joinToString("") { "%02x".format(it) }
                    return Pair(buf, hash)
                }
            }
        }

        // Read from APK asset
        context.assets.open(assetPath).use { stream ->
            val bytes = stream.readBytes()
            val hash = md.digest(bytes).joinToString("") { "%02x".format(it) }
            val directBuf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            directBuf.put(bytes)
            directBuf.rewind()
            return Pair(directBuf, hash)
        }
    }

    fun onThermalStatusChanged(status: Int) {
        val newMode = if (status >= 2) TierMode.FAST else TierMode.DUAL
        if (newMode != currentMode) {
            val ts = SystemClock.elapsedRealtimeNanos()
            Log.i(
                TAG,
                "THERMAL TRANSITION: status=$status -> switching $currentMode to $newMode (timestamp=$ts ns)"
            )
            currentMode = newMode
        }
    }

    fun onFpsMeasured(fps: Float) {
        if (fps < 28.0f && currentMode == TierMode.DUAL) {
            val ts = SystemClock.elapsedRealtimeNanos()
            Log.i(
                TAG,
                "FPS TRANSITION: sustained fps=%.1f < 28 -> switching DUAL to FAST (timestamp=%d ns)".format(
                    fps, ts
                )
            )
            currentMode = TierMode.FAST
        } else if (fps >= 29.5f && currentMode == TierMode.FAST) {
            val ts = SystemClock.elapsedRealtimeNanos()
            Log.i(
                TAG,
                "FPS RECOVERY: fps=%.1f >= 29.5 -> restoring FAST to DUAL (timestamp=%d ns)".format(
                    fps, ts
                )
            )
            currentMode = TierMode.DUAL
        }
    }

    /**
     * Submits a camera YUV frame for 12 Hz async inference with fast-path scene detection.
     */
    fun processFrame(image: Image): Boolean {
        if (!isInitialized.get()) return false

        val isDual = (currentMode == TierMode.DUAL)

        // L.7 (a) / Item 4: Interpreter presence check (require general only when isDual)
        if (interpLandscape == null || (isDual && interpGeneral == null)) {
            if (!loggedFirstInferenceFailure) {
                loggedFirstInferenceFailure = true
                Log.e(
                    TAG,
                    "INFERENCE FAILURE (session first): (a) interpreter is null (landscape=${interpLandscape != null}, general=${interpGeneral != null}, isDual=$isDual), returning false without updating matte"
                )
            }
            return false
        }

        val nowMs = SystemClock.elapsedRealtime()
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val width = image.width
        val height = image.height

        // 1. Calculate 16-bin luma histogram total variation for fast-path trigger
        val histDelta = computeLumaHistDelta(yBuffer, width, height, yRowStride)
        val fastPathTriggered = (hasPrevHistogram && histDelta > FAST_PATH_HIST_DELTA)

        if (!fastPathTriggered && (nowMs - lastDispatchTimeMs < MIN_INTERVAL_MS)) {
            // Cadence gate: reuse latest smoothed alpha on intermediate frames
            return false
        }

        if (fastPathTriggered) {
            Log.i(
                TAG,
                "FAST-PATH FIRED: luma hist delta=%.4f > %.2f (resetting EMA to prevent smear)".format(
                    histDelta, FAST_PATH_HIST_DELTA
                )
            )
        }

        lastDispatchTimeMs = nowMs

        // 2. Preprocessing: Stretch resize camera frame directly to model shapes
        preprocessStretchYuv(image, 256, 144, inDtypeL, inScaleL, inZeroL, inBufL)

        // 3. Inference on Landscape
        val t0L = SystemClock.elapsedRealtimeNanos()
        var runOkL = false
        var invokeFailedL = false
        try {
            inBufL.rewind(); outBufL.rewind()
            interpLandscape?.run(inBufL, outBufL)
            runOkL = true
        } catch (t: Throwable) {
            invokeFailedL = true
            Log.w(TAG, "Landscape inference failed: ${t.message}")
        }
        val dtLMs = (SystemClock.elapsedRealtimeNanos() - t0L) / 1_000_000.0

        val doStretch = STRETCH_MATTE

        var readOkL = false
        var readFailedL = false
        if (runOkL) {
            try {
                outBufL.rewind()
                if (outDtypeL == DataType.FLOAT32) {
                    outBufL.asFloatBuffer().get(probL)
                    if (doStretch) {
                        for (i in 0 until 144 * 256) {
                            probL[i] = Math.min(1.0f, probL[i] * 2.0f)
                        }
                    }
                } else {
                    for (i in 0 until 144 * 256) {
                        val q = outBufL.get().toInt()
                        var p = ((q - outZeroL) * outScaleL).coerceIn(0.0f, 1.0f)
                        if (doStretch) {
                            p = Math.min(1.0f, p * 2.0f)
                        }
                        probL[i] = p
                    }
                }
                upsampleLandscapeBilinear(probL, probUpsampledL)
                readOkL = true
            } catch (t: Throwable) {
                readFailedL = true
                Log.w(TAG, "Landscape output read failed: ${t.message}")
            }
        }

        // L.7 (b)/(c): Return false on failure without upgrading or modifying matte
        if (!runOkL || !readOkL) {
            if (!loggedFirstInferenceFailure) {
                loggedFirstInferenceFailure = true
                val cause = if (invokeFailedL) "(b) invoke() threw exception" else "(c) output tensor read failed"
                Log.e(
                    TAG,
                    "INFERENCE FAILURE (session first): Landscape model failed due to $cause, returning false without updating matte"
                )
            }
            return false
        }

        var dtGMs = 0.0

        if (isDual) {
            preprocessStretchYuv(image, 256, 256, inDtypeG, inScaleG, inZeroG, inBufG)

            val t0G = SystemClock.elapsedRealtimeNanos()
            var runOkG = false
            var invokeFailedG = false
            try {
                inBufG.rewind(); outBufG.rewind()
                interpGeneral?.run(inBufG, outBufG)
                runOkG = true
            } catch (t: Throwable) {
                invokeFailedG = true
                Log.w(TAG, "General inference failed: ${t.message}")
            }
            dtGMs = (SystemClock.elapsedRealtimeNanos() - t0G) / 1_000_000.0

            var readOkG = false
            var readFailedG = false
            if (runOkG) {
                try {
                    outBufG.rewind()
                    if (outDtypeG == DataType.FLOAT32) {
                        outBufG.asFloatBuffer().get(probG)
                        if (doStretch) {
                            for (i in 0 until 256 * 256) {
                                probG[i] = Math.min(1.0f, probG[i] * 2.0f)
                            }
                        }
                    } else {
                        for (i in 0 until 256 * 256) {
                            val q = outBufG.get().toInt()
                            var p = ((q - outZeroG) * outScaleG).coerceIn(0.0f, 1.0f)
                            if (doStretch) {
                                p = Math.min(1.0f, p * 2.0f)
                            }
                            probG[i] = p
                        }
                    }
                    readOkG = true
                } catch (t: Throwable) {
                    readFailedG = true
                    Log.w(TAG, "General output read failed: ${t.message}")
                }
            }

            // L.7 (b)/(c): Check General model in DUAL mode
            if (!runOkG || !readOkG) {
                if (!loggedFirstInferenceFailure) {
                    loggedFirstInferenceFailure = true
                    val cause = if (invokeFailedG) "(b) invoke() threw exception" else "(c) output tensor read failed"
                    Log.e(
                        TAG,
                        "INFERENCE FAILURE (session first): General model failed due to $cause, returning false without updating matte"
                    )
                }
                return false
            }

            // Pixel-max combine at common 256x256 resolution
            for (i in 0 until 256 * 256) {
                probCombined[i] = Math.max(probUpsampledL[i], probG[i])
            }
        } else {
            // Fast mode: landscape alone
            System.arraycopy(probUpsampledL, 0, probCombined, 0, 256 * 256)
        }

        // 4. Threshold & Telemetry
        val thr = if (isDual) THR_DUAL else THR_FAST
        var fgCount = 0
        val hardThreshold = MATTE_HARD_THRESHOLD

        var fg045Count = 0
        var fg065Count = 0
        var minA = 1.0f
        var maxA = 0.0f
        var sumA = 0.0

        for (i in 0 until 256 * 256) {
            val p = probCombined[i]
            if (p >= thr) fgCount++
            if (p >= 0.45f) fg045Count++
            if (p >= 0.65f) fg065Count++
            if (p < minA) minA = p
            if (p > maxA) maxA = p
            sumA += p

            if (hardThreshold) {
                probCombined[i] = if (p >= thr) 1.0f else 0.0f
            }
        }
        val fgFraction = fgCount.toFloat() / (256f * 256f)
        val fg045 = fg045Count.toFloat() / (256f * 256f)
        val fg065 = fg065Count.toFloat() / (256f * 256f)
        val meanA = (sumA / (256.0 * 256.0)).toFloat()

        // 5. EMA Temporal Smoothing (alpha = 0.25)
        if (!emaInitialized || fastPathTriggered) {
            System.arraycopy(probCombined, 0, emaMatte, 0, 256 * 256)
            emaInitialized = true
        } else {
            val alpha = EMA_ALPHA
            val oneMinusAlpha = 1.0f - alpha
            for (i in 0 until 256 * 256) {
                emaMatte[i] = oneMinusAlpha * emaMatte[i] + alpha * probCombined[i]
            }
        }

        // 6. Write to output byte array for Vulkan engine
        synchronized(alphaLock) {
            for (i in 0 until 256 * 256) {
                outputAlphaBytes[i] = (emaMatte[i] * 255.0f).toInt().coerceIn(0, 255).toByte()
            }
        }

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "L0_STATS: mode=%s fg045=%.4f fg065=%.4f min=%.4f max=%.4f mean=%.4f stretch=%b",
                currentMode.name, fg045, fg065, minA, maxA, meanA, doStretch
            )
        )

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "MATTE CYCLE: mode=%s L=%.2fms G=%.2fms (combined=%.2fms) fg=%.4f (target 0.3125) hardThr=%b",
                currentMode.name, dtLMs, dtGMs, dtLMs + dtGMs, fgFraction, hardThreshold
            )
        )
        return true
    }

    /**
     * Thread-safe snapshot copy of the latest 256x256 alpha matte.
     */
    fun getLatestAlphaMatte(dst: ByteArray) {
        synchronized(alphaLock) {
            System.arraycopy(outputAlphaBytes, 0, dst, 0, Math.min(dst.size, outputAlphaBytes.size))
        }
    }

    /**
     * Bilinear upsampling of Landscape [144 x 256] to [256 x 256] common resolution.
     */
    private fun upsampleLandscapeBilinear(src: FloatArray, dst: FloatArray) {
        val srcH = 144
        val srcW = 256
        val dstH = 256
        val dstW = 256

        val scaleY = (srcH - 1).toFloat() / (dstH - 1).toFloat()

        for (dy in 0 until dstH) {
            val sy = dy * scaleY
            val y0 = sy.toInt()
            val y1 = Math.min(y0 + 1, srcH - 1)
            val fy = sy - y0
            val oneMinusFy = 1.0f - fy

            val row0 = y0 * srcW
            val row1 = y1 * srcW
            val dstRow = dy * dstW

            // srcW == dstW == 256; horizontal scaling is 1:1
            for (x in 0 until dstW) {
                val v0 = src[row0 + x]
                val v1 = src[row1 + x]
                dst[dstRow + x] = oneMinusFy * v0 + fy * v1
            }
        }
    }

    /**
     * Stretches YUV_420_888 frame directly to [dstW x dstH x 3] buffer (FLOAT32 or INT8).
     */
    private fun preprocessStretchYuv(
        image: Image,
        dstW: Int,
        dstH: Int,
        inDtype: DataType,
        scale: Float,
        zero: Int,
        outBuf: ByteBuffer
    ) {
        outBuf.rewind()
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val srcW = image.width
        val srcH = image.height

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val isFloat = (inDtype == DataType.FLOAT32)
        val invScale = if (!isFloat && scale > 0f) 1.0f / (scale * 255.0f) else 1.0f

        for (dy in 0 until dstH) {
            val sy = (dy * srcH) / dstH
            val uvY = sy / 2
            val yOffset = sy * yRowStride
            val uOffsetBase = uvY * uRowStride
            val vOffsetBase = uvY * vRowStride

            for (dx in 0 until dstW) {
                val sx = (dx * srcW) / dstW
                val uvX = sx / 2

                val yVal = yBuf.get(yOffset + sx).toInt() and 0xFF
                val uVal = (uBuf.get(uOffsetBase + uvX * uPixelStride).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(vOffsetBase + uvX * vPixelStride).toInt() and 0xFF) - 128

                // BT.601 YUV -> RGB conversion
                val r = (yVal + 1.402f * vVal).coerceIn(0f, 255f)
                val g = (yVal - 0.344136f * uVal - 0.714136f * vVal).coerceIn(0f, 255f)
                val b = (yVal + 1.772f * uVal).coerceIn(0f, 255f)

                if (isFloat) {
                    outBuf.putFloat((r / 127.5f) - 1.0f)
                    outBuf.putFloat((g / 127.5f) - 1.0f)
                    outBuf.putFloat((b / 127.5f) - 1.0f)
                } else {
                    val qR = Math.round(r * invScale + zero).coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
                    val qG = Math.round(g * invScale + zero).coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
                    val qB = Math.round(b * invScale + zero).coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()

                    outBuf.put(qR)
                    outBuf.put(qG)
                    outBuf.put(qB)
                }
            }
        }
        outBuf.rewind()
    }

    /**
     * Computes 16-bin luma histogram total variation difference for fast path.
     */
    private fun computeLumaHistDelta(
        yBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int
    ): Float {
        val currHist = FloatArray(16)
        var totalSamples = 0

        // Subsample every 16th pixel
        for (y in 0 until height step 16) {
            val rowOffset = y * rowStride
            for (x in 0 until width step 16) {
                val yVal = yBuffer.get(rowOffset + x).toInt() and 0xFF
                val bin = yVal shr 4 // 0..15
                currHist[bin] += 1.0f
                totalSamples++
            }
        }

        if (totalSamples > 0) {
            val invTotal = 1.0f / totalSamples.toFloat()
            for (i in 0 until 16) {
                currHist[i] *= invTotal
            }
        }

        if (!hasPrevHistogram) {
            System.arraycopy(currHist, 0, prevHistogram, 0, 16)
            hasPrevHistogram = true
            return 0.0f
        }

        var diffSum = 0.0f
        for (i in 0 until 16) {
            diffSum += Math.abs(currHist[i] - prevHistogram[i])
        }
        System.arraycopy(currHist, 0, prevHistogram, 0, 16)
        return diffSum * 0.5f // Total variation in [0, 1]
    }

    fun runFloatStopgapBenchmark(): String {
        Log.i(TAG, "Starting L.1 FLOAT16 STOPGAP CPU XNNPACK BENCHMARK (50 runs each)...")
        val (bufL, hashL) = loadAndHashModel("models/mp_landscape_fixed.tflite")
        val (bufG, hashG) = loadAndHashModel("models/mp_general_fixed.tflite")
        Log.i(TAG, "Loaded float landscape sha256: $hashL")
        Log.i(TAG, "Loaded float general sha256:   $hashG")

        val optsCpuL = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        val optsCpuG = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }

        val interpFloatL = Interpreter(bufL, optsCpuL)
        val interpFloatG = Interpreter(bufG, optsCpuG)

        val inFloatL = ByteBuffer.allocateDirect(1 * 144 * 256 * 3 * 4).order(ByteOrder.nativeOrder())
        val outFloatL = ByteBuffer.allocateDirect(1 * 144 * 256 * 1 * 4).order(ByteOrder.nativeOrder())
        val inFloatG = ByteBuffer.allocateDirect(1 * 256 * 256 * 3 * 4).order(ByteOrder.nativeOrder())
        val outFloatG = ByteBuffer.allocateDirect(1 * 256 * 256 * 1 * 4).order(ByteOrder.nativeOrder())

        // Warmup (5 runs)
        for (w in 0 until 5) {
            inFloatL.rewind(); outFloatL.rewind(); interpFloatL.run(inFloatL, outFloatL)
            inFloatG.rewind(); outFloatG.rewind(); interpFloatG.run(inFloatG, outFloatG)
        }

        // 1. 50 runs Landscape alone
        val timesL = DoubleArray(50)
        for (i in 0 until 50) {
            inFloatL.rewind(); outFloatL.rewind()
            val t0 = SystemClock.elapsedRealtimeNanos()
            interpFloatL.run(inFloatL, outFloatL)
            val dt = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            timesL[i] = dt
        }

        // 2. 50 runs General alone
        val timesG = DoubleArray(50)
        for (i in 0 until 50) {
            inFloatG.rewind(); outFloatG.rewind()
            val t0 = SystemClock.elapsedRealtimeNanos()
            interpFloatG.run(inFloatG, outFloatG)
            val dt = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            timesG[i] = dt
        }

        // 3. 50 runs Dual back-to-back
        val timesDual = DoubleArray(50)
        for (i in 0 until 50) {
            inFloatL.rewind(); outFloatL.rewind()
            inFloatG.rewind(); outFloatG.rewind()
            val t0 = SystemClock.elapsedRealtimeNanos()
            interpFloatL.run(inFloatL, outFloatL)
            interpFloatG.run(inFloatG, outFloatG)
            val dt = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
            timesDual[i] = dt
        }

        val minL = timesL.minOrNull() ?: 0.0
        val maxL = timesL.maxOrNull() ?: 0.0
        val meanL = timesL.average()

        val minG = timesG.minOrNull() ?: 0.0
        val maxG = timesG.maxOrNull() ?: 0.0
        val meanG = timesG.average()

        val minDual = timesDual.minOrNull() ?: 0.0
        val maxDual = timesDual.maxOrNull() ?: 0.0
        val meanDual = timesDual.average()

        val report = String.format(
            Locale.US,
            "FLOAT_BENCHMARK: Landscape min=%.2fms mean=%.2fms max=%.2fms | General min=%.2fms mean=%.2fms max=%.2fms | Dual min=%.2fms mean=%.2fms max=%.2fms",
            minL, meanL, maxL, minG, meanG, maxG, minDual, meanDual, maxDual
        )
        Log.i(TAG, report)

        interpFloatL.close()
        interpFloatG.close()
        return report
    }

    fun close() {
        interpLandscape?.close()
        interpGeneral?.close()
        nnapiDelegateL?.close()
        nnapiDelegateG?.close()
        isInitialized.set(false)
        Log.i(TAG, "DualMatteSegmenter closed")
    }
}
