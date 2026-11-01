package com.zenith.auxcam

import android.content.Context
import android.media.Image
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest

/** A bounded upright YUV sample. Only byte copies cross the camera callback boundary. */
internal data class BokehInput(val samples: ByteArray, val timestampNs: Long) {
    fun rgb(): BokehFrame {
        val rgb = ByteArray(samples.size)
        var i = 0
        while (i < samples.size) {
            val y = samples[i].toInt() and 255
            val u = (samples[i + 1].toInt() and 255) - 128
            val v = (samples[i + 2].toInt() and 255) - 128
            rgb[i] = (y + 1.402f * v).toInt().coerceIn(0, 255).toByte()
            rgb[i + 1] = (y - .344136f * u - .714136f * v).toInt().coerceIn(0, 255).toByte()
            rgb[i + 2] = (y + 1.772f * u).toInt().coerceIn(0, 255).toByte()
            i += 3
        }
        return BokehFrame(WIDTH, HEIGHT, rgb, timestampNs)
    }

    companion object {
        const val WIDTH = 384
        const val HEIGHT = 512
        fun copy(image: Image): BokehInput {
            val planes = image.planes
            fun plane(index: Int) = planes[index].let { FrameData.Plane(it.buffer, it.rowStride, it.pixelStride) }
            return BokehInput(FrameData.sampleYuv(plane(0), plane(1), plane(2), image.width, image.height,
                WIDTH, HEIGHT, DeviceProfile.PREVIEW_ROTATION_90), image.timestamp)
        }
    }
}

internal object BokehAssets {
    fun bytes(context: Context, asset: String): ByteArray {
        val ledger = JSONObject(context.assets.open("models/bokeh-models.json").bufferedReader().use { it.readText() }).getJSONArray("files")
        val spec = (0 until ledger.length()).map { ledger.getJSONObject(it) }.first { it.getString("asset") == asset }
        val bytes = context.assets.open(asset).use { it.readBytes() }
        require(bytes.size == spec.getInt("size")) { "Wrong model size: $asset" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == spec.getString("sha256")) { "Model SHA-256 mismatch: $asset" }
        return bytes
    }
}

internal fun createBokehEngine(context: Context, model: BokehModel): BokehEngine = when (model) {
    BokehModel.MediaPipeAuto, BokehModel.MediaPipeDual -> LegacyBokehEngine(context, model)
    BokehModel.MediaPipeLandscape, BokehModel.MediaPipeGeneral -> SingleMediaPipeEngine(context, model)
    BokehModel.MODNet, BokehModel.RVM -> OnnxBokehEngine(context, model)
}

private class LegacyBokehEngine(context: Context, private val model: BokehModel) : BokehEngine {
    private val segmenter = DualMatteSegmenter(context, packagedOnly = true)
    init {
        try {
            // Verify both float assets before allowing the original loader/delegate path.
            BokehAssets.bytes(context, "models/mp_landscape_fixed_float.tflite")
            BokehAssets.bytes(context, "models/mp_general_fixed_float.tflite")
            check(segmenter.initialize()) { "MediaPipe initialization/delegation failed" }
            check(segmenter.currentMode == DualMatteSegmenter.TierMode.DUAL) { "General model unavailable" }
        } catch (t: Throwable) { segmenter.close(); throw t }
    }
    override val description: String
        get() = "TFLite · NNAPI requested (placement unverified) · ${segmenter.currentMode}"
    override fun infer(frame: BokehFrame): ByteArray {
        check(segmenter.preprocessRgb(frame) && segmenter.runInference()) { "MediaPipe inference failed" }
        return ByteArray(256 * 256).also { segmenter.getLatestAlphaMatte(it) }
    }
    override fun policy(thermal: Int, fps: Float) {
        if (model == BokehModel.MediaPipeAuto) {
            segmenter.onThermalStatusChanged(thermal)
            // Thermal takes precedence over FPS recovery.
            if (thermal < 2) segmenter.onFpsMeasured(fps)
        }
    }
    override fun reset() = segmenter.resetTemporalState()
    override fun close() = segmenter.close()
}

private class SingleMediaPipeEngine(context: Context, model: BokehModel) : BokehEngine {
    private val h = if (model == BokehModel.MediaPipeLandscape) 144 else 256
    private val bytes = BokehAssets.bytes(context, if (h == 144) "models/mp_landscape_fixed_float.tflite" else "models/mp_general_fixed_float.tflite")
    private val modelBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    private val interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(2); setUseXNNPACK(true) })
    private val input = ByteBuffer.allocateDirect(256 * h * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(256 * h * 4).order(ByteOrder.nativeOrder())
    override val description = "TFLite CPU/XNNPACK · 2 threads · 256 × $h"
    init {
        try {
            require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, h, 256, 3)))
            require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, h, 256, 1)))
            require(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32)
            require(interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32)
        } catch (t: Throwable) { interpreter.close(); throw t }
    }
    override fun infer(frame: BokehFrame): ByteArray {
        frame.writeTensor(input.asFloatBuffer(), 256, h, nchw = false)
        input.rewind(); output.rewind()
        interpreter.run(input, output)
        val alpha = FloatArray(256 * h)
        output.rewind(); output.asFloatBuffer().get(alpha)
        return BokehMask.sensorBytes(alpha, 256, h, DeviceProfile.PREVIEW_ROTATION_90)
    }
    override fun close() = interpreter.close()
}

private class OnnxBokehEngine(context: Context, private val model: BokehModel) : BokehEngine {
    private val env = OrtEnvironment.getEnvironment() // Process-owned singleton; never close per engine.
    private val session: OrtSession
    private val input = ByteBuffer.allocateDirect(BokehInput.WIDTH * BokehInput.HEIGHT * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private data class Recurrent(val data: FloatArray, val shape: LongArray)
    private var recurrent: List<Recurrent> = emptyList()
    override val description = "ONNX CPU · 2 threads · 384 × 512" + if (model == BokehModel.RVM) " · ds 0.5 · recurrent" else " · RGB [-1,1]"
    init {
        val path = if (model == BokehModel.MODNet) "models/optional/modnet.onnx" else "models/optional/rvm_mobilenetv3_fp32.onnx"
        val bytes = BokehAssets.bytes(context, path)
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(bytes, options)
        }
        try {
            val expected = if (model == BokehModel.MODNet) setOf("input") else setOf("src", "r1i", "r2i", "r3i", "r4i", "downsample_ratio")
            require(session.inputNames == expected) { "Unexpected ONNX input names" }
            require(session.inputInfo.values.all { (it.info as? TensorInfo)?.type == OnnxJavaType.FLOAT }) { "Expected FP32 inputs" }
            val outputName = if (model == BokehModel.MODNet) "output" else "pha"
            require(session.outputNames.contains(outputName)) { "Missing alpha output" }
            require((session.outputInfo.getValue(outputName).info as TensorInfo).type == OnnxJavaType.FLOAT)
            val imageInfo = session.inputInfo.getValue(if (model == BokehModel.MODNet) "input" else "src").info as TensorInfo
            require(imageInfo.shape.size == 4 && imageInfo.shape[1] == 3L) { "Expected RGB NCHW" }
        } catch (t: Throwable) { session.close(); throw t }
    }
    override fun infer(frame: BokehFrame): ByteArray {
        frame.writeTensor(input, BokehInput.WIDTH, BokehInput.HEIGHT, nchw = true, signed = model == BokehModel.MODNet)
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            tensors[if (model == BokehModel.MODNet) "input" else "src"] = OnnxTensor.createTensor(env, input,
                longArrayOf(1, 3, BokehInput.HEIGHT.toLong(), BokehInput.WIDTH.toLong()))
            if (model == BokehModel.RVM) {
                repeat(4) { i ->
                    val history = recurrent.getOrNull(i) ?: Recurrent(floatArrayOf(0f), longArrayOf(1, 1, 1, 1))
                    tensors["r${i + 1}i"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(history.data), history.shape)
                }
                tensors["downsample_ratio"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(.5f)), longArrayOf(1))
            }
            val names = if (model == BokehModel.MODNet) setOf("output") else setOf("pha", "r1o", "r2o", "r3o", "r4o")
            session.run(tensors, names).use { result ->
                val alpha = result.get(if (model == BokehModel.MODNet) "output" else "pha").get() as OnnxTensor
                val shape = alpha.info.shape
                require(shape.contentEquals(longArrayOf(1, 1, BokehInput.HEIGHT.toLong(), BokehInput.WIDTH.toLong()))) { "Unexpected alpha shape" }
                val values = FloatArray(BokehInput.WIDTH * BokehInput.HEIGHT)
                alpha.floatBuffer.get(values)
                val mask = BokehMask.sensorBytes(values, BokehInput.WIDTH, BokehInput.HEIGHT, DeviceProfile.PREVIEW_ROTATION_90)
                if (model == BokehModel.RVM) {
                    recurrent = (1..4).map { i ->
                        val tensor = result.get("r${i}o").get() as OnnxTensor
                        val buffer = tensor.floatBuffer
                        val history = FloatArray(buffer.remaining()).also { buffer.get(it) }
                        require(history.all { it.isFinite() }) { "Nonfinite recurrent state" }
                        Recurrent(history, tensor.info.shape.clone()) // Copy before Result.close().
                    }
                }
                return mask
            }
        } finally { tensors.values.forEach { it.close() } }
    }
    override fun reset() { recurrent = emptyList() }
    override fun close() { reset(); session.close() }
}
