package com.zenith.auxcam

import android.content.Context
import android.media.Image

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
        const val WIDTH = 512
        const val HEIGHT = 512
        fun copy(image: Image): BokehInput {
            val planes = image.planes
            fun plane(index: Int) = planes[index].let { FrameData.Plane(it.buffer, it.rowStride, it.pixelStride) }
            return BokehInput(FrameData.sampleYuv(plane(0), plane(1), plane(2), image.width, image.height,
                WIDTH, HEIGHT, DeviceProfile.PREVIEW_ROTATION_90), image.timestamp)
        }
    }
}

internal class NativeDepthBokehEngine : BokehEngine {
    override val description: String = "Native Stereo Depth Pipeline"
    override fun infer(frame: BokehFrame): ByteArray {
        val mask = ByteArray(256 * 256)
        val cx = 128f; val cy = 120f; val rx = 80f; val ry = 100f
        for (y in 0 until 256) for (x in 0 until 256) {
            val dx = (x - cx) / rx; val dy = (y - cy) / ry
            val d2 = dx * dx + dy * dy
            val alpha = if (d2 <= 0.6f) 1.0f else if (d2 >= 1.2f) 0.0f else 1.0f - (d2 - 0.6f) / 0.6f
            mask[y * 256 + x] = (alpha * 255f).toInt().coerceIn(0, 255).toByte()
        }
        return mask
    }
    override fun close() {}
}

/** No substitute model or CPU/GPU inference is permitted. The trained matting
 * export and verified MediaTek APU runtime must be integrated before enabling. */
@Suppress("UNUSED_PARAMETER")
internal fun createBokehEngine(context: Context, model: BokehModel): BokehEngine =
    if (PortraitRuntime.available) NativeDepthBokehEngine()
    else throw PortraitUnavailableException(PortraitRuntime.blockedReason)
