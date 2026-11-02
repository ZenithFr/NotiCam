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

/** No substitute model or CPU/GPU inference is permitted. The trained matting
 * export and verified MediaTek APU runtime must be integrated before enabling. */
@Suppress("UNUSED_PARAMETER")
internal fun createBokehEngine(context: Context, model: BokehModel): BokehEngine =
    throw PortraitUnavailableException(PortraitRuntime.blockedReason)
