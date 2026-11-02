package com.zenith.auxcam

import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

/** Copied upright RGB, never an Image, plane buffer or camera AHB. */
internal data class BokehFrame(val width: Int, val height: Int, val rgb: ByteArray, val timestampNs: Long) {
    init { require(width > 0 && height > 0 && rgb.size == width * height * 3) }

    fun writeTensor(out: FloatBuffer, w: Int, h: Int, nchw: Boolean, signed: Boolean = false) {
        require(out.capacity() == w * h * 3)
        out.clear()
        for (y in 0 until h) for (x in 0 until w) {
            val source = ((y * height / h) * width + x * width / w) * 3
            for (channel in 0..2) {
                val value = (rgb[source + channel].toInt() and 255) / 255f
                val index = if (nchw) channel * w * h + y * w + x else (y * w + x) * 3 + channel
                out.put(index, if (signed) value * 2f - 1f else value)
            }
        }
        out.rewind()
    }
}

internal object BokehMask {
    /** Empty masks are not a license to blur the whole frame. Depth may still
     * provide a foreground surface; without it, the renderer must stay sharp. */
    fun hasForeground(mask: ByteArray): Boolean = mask.isNotEmpty() &&
        mask.count { (it.toInt() and 255) >= 128 } >= maxOf(1, mask.size / 1000)

    /** Validate floats BEFORE quantizing; a byte mask cannot reveal NaNs. */
    fun sensorBytes(alpha: FloatArray, width: Int, height: Int, rotate90: Boolean): ByteArray {
        require(width > 0 && height > 0 && alpha.size == width * height)
        require(alpha.all { it.isFinite() && it >= -.001f && it <= 1.001f }) { "Nonfinite/out-of-range alpha" }
        val upright = ByteArray(256 * 256)
        for (y in 0 until 256) for (x in 0 until 256) {
            val sx = x * (width - 1f) / 255f
            val sy = y * (height - 1f) / 255f
            val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
            val x1 = minOf(x0 + 1, width - 1); val y1 = minOf(y0 + 1, height - 1)
            val fx = sx - x0; val fy = sy - y0
            val top = alpha[y0 * width + x0] * (1f - fx) + alpha[y0 * width + x1] * fx
            val bottom = alpha[y1 * width + x0] * (1f - fx) + alpha[y1 * width + x1] * fx
            upright[y * 256 + x] = ((top * (1f - fy) + bottom * fy).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
        return ByteArray(upright.size) { i ->
            upright[FrameData.sensorMatteIndex(i % 256, i / 256, 256, rotate90)]
        }
    }

    fun percentile(samples: List<Double>, fraction: Double): Double {
        require(samples.isNotEmpty() && samples.all { it.isFinite() && it >= 0.0 })
        val sorted = samples.sorted()
        return sorted[(kotlin.math.ceil(fraction.coerceIn(0.0, 1.0) * sorted.size).toInt() - 1).coerceAtLeast(0)]
    }
}
