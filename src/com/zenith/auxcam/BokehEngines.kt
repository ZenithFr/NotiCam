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
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    BokehModel.MediaPipeAuto -> AutoPortraitEngine(context)
    BokehModel.MODNet -> OnnxBokehEngine(context)
}

/** One general person model; no max-fusion false positives or lagging dual EMA. */
private class AutoPortraitEngine(context: Context) : BokehEngine {
    private val bytes = BokehAssets.bytes(context, "models/mp_general_fixed_float.tflite")
    private val modelBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    private val input = ByteBuffer.allocateDirect(256 * 256 * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(256 * 256 * 4).order(ByteOrder.nativeOrder())
    private class Session(val interpreter: Interpreter, val delegate: NnApiDelegate?, val label: String) : AutoCloseable {
        override fun close() { try { interpreter.close() } finally { delegate?.close() } }
    }
    private val session = InferenceBackend.open(NnapiDevices.available(), accelerated = { device ->
        var delegate: NnApiDelegate? = null
        try {
            modelBuffer.rewind()
            val nnOptions = NnApiDelegate.Options().apply {
                setUseNnapiCpu(false)
                setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_FAST_SINGLE_ANSWER)
                if (device != null) setAcceleratorName(device)
            }
            delegate = NnApiDelegate(nnOptions)
            val interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(2); addDelegate(delegate) })
            Session(interpreter, delegate, "NNAPI · ${device ?: "system selection"} · placement unverified")
        } catch (failure: Throwable) { delegate?.close(); throw failure }
    }, cpu = {
        modelBuffer.rewind()
        Session(Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(2); setUseXNNPACK(true) }), null,
            "CPU/XNNPACK fallback · accelerator unavailable or rejected")
    }, validate = { active ->
        require(active.interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 256, 256, 3)))
        require(active.interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 256, 256, 1)))
        require(active.interpreter.getInputTensor(0).dataType() == DataType.FLOAT32)
        require(active.interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32)
        // A constructed delegate is not evidence that a model can actually run.
        input.rewind(); output.rewind()
        active.interpreter.run(input, output)
        val warmup = FloatArray(256 * 256)
        output.rewind(); output.asFloatBuffer().get(warmup)
        require(warmup.all { it.isFinite() && it >= -.001f && it <= 1.001f })
    })
    override val description: String get() = "Auto · ${session.label} · 256 × 256"
    override fun infer(frame: BokehFrame): ByteArray {
        frame.writeTensor(input.asFloatBuffer(), 256, 256, nchw = false)
        input.rewind(); output.rewind()
        session.interpreter.run(input, output)
        val alpha = FloatArray(256 * 256)
        output.rewind(); output.asFloatBuffer().get(alpha)
        return BokehMask.sensorBytes(alpha, 256, 256, DeviceProfile.PREVIEW_ROTATION_90)
    }
    override fun close() = session.close()
}

/** Quality option is MODNet only. No recurrent model or unused tensors retained. */
private class OnnxBokehEngine(context: Context) : BokehEngine {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val input = ByteBuffer.allocateDirect(BokehInput.WIDTH * BokehInput.HEIGHT * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    override val description = "Portrait quality · MODNet · CPU · 384 × 512 · RGB [-1,1]"
    init {
        val bytes = BokehAssets.bytes(context, "models/optional/modnet.onnx")
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(bytes, options)
        }
        try {
            require(session.inputNames == setOf("input")) { "Unexpected MODNet inputs" }
            val imageInfo = session.inputInfo.getValue("input").info as TensorInfo
            require(imageInfo.type == OnnxJavaType.FLOAT && imageInfo.shape.size == 4 && imageInfo.shape[1] == 3L)
            require(session.outputNames.contains("output"))
            require((session.outputInfo.getValue("output").info as TensorInfo).type == OnnxJavaType.FLOAT)
        } catch (failure: Throwable) { session.close(); throw failure }
    }
    override fun infer(frame: BokehFrame): ByteArray {
        frame.writeTensor(input, BokehInput.WIDTH, BokehInput.HEIGHT, nchw = true, signed = true)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, BokehInput.HEIGHT.toLong(), BokehInput.WIDTH.toLong())).use { tensor ->
            session.run(mapOf("input" to tensor), setOf("output")).use { result ->
                val alpha = result.get("output").get() as OnnxTensor
                require(alpha.info.shape.contentEquals(longArrayOf(1, 1, BokehInput.HEIGHT.toLong(), BokehInput.WIDTH.toLong())))
                val values = FloatArray(BokehInput.WIDTH * BokehInput.HEIGHT)
                alpha.floatBuffer.get(values)
                return BokehMask.sensorBytes(values, BokehInput.WIDTH, BokehInput.HEIGHT, DeviceProfile.PREVIEW_ROTATION_90)
            }
        }
    }
    override fun close() = session.close()
}
