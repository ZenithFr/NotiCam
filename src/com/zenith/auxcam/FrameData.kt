package com.zenith.auxcam

import java.nio.ByteBuffer
import kotlin.math.abs

/** Platform-independent buffer rules shared by preview and capture. */
internal object FrameData {
    const val DEPTH_MAX_AGE_NS = 100_000_000L
    const val MATTE_MAX_AGE_NS = 500_000_000L

    /** Same sentinel/sampling rule as the native preview and still pipeline. */
    fun hasUsableDepth(bytes: ByteArray?): Boolean = bytes != null &&
        (bytes.indices step 16).any { (bytes[it].toInt() and 255) in 6..254 }

    data class DepthFrame(val timestamp: Long, val bytes: ByteArray)

    /** Read only valid pixels, including when the last row has no trailing padding. */
    fun packPlane(source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        require(width > 0 && height > 0 && pixelStride > 0)
        val rowBytes = (width - 1L) * pixelStride + 1L
        require(rowStride >= rowBytes)
        val origin = source.position()
        val lastByte = origin + (height - 1L) * rowStride + (width - 1L) * pixelStride
        require(lastByte < source.limit()) { "Plane buffer is shorter than its geometry" }
        val count = width.toLong() * height
        require(count <= Int.MAX_VALUE)
        return ByteArray(count.toInt()) { index ->
            source.get(origin + (index / width) * rowStride + (index % width) * pixelStride)
        }
    }

    fun isFresh(source: Long, target: Long, maxAge: Long): Boolean =
        source > 0 && target > 0 && maxAge >= 0 &&
            (if (target >= source) target - source else source - target) <= maxAge

    /** Bounded YUV sampling with BULK row reads, not ~590k absolute direct-buffer
     * gets on the camera callback. Preserves the original pixel mapping exactly.
     * Buffer positions, chroma pixel strides and an unpadded final row are honored.
     */
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun sampleYuv(y: Plane, u: Plane, v: Plane, width: Int, height: Int,
                  outWidth: Int, outHeight: Int, rotate90: Boolean): ByteArray {
        require(width > 0 && height > 0 && outWidth > 0 && outHeight > 0)
        require(outWidth.toLong() * outHeight * 3 <= Int.MAX_VALUE)
        class Rows(plane: Plane, columns: Int, rows: Int) {
            val buffer = plane.buffer.duplicate()
            val origin = buffer.position()
            val stride = plane.rowStride
            val pixel = plane.pixelStride
            val length = ((columns - 1L) * pixel + 1).also {
                require(pixel > 0 && it <= stride && it <= Int.MAX_VALUE)
                require(origin + (rows - 1L) * stride + it <= buffer.limit()) { "Truncated YUV plane" }
            }.toInt()
            val bytes = ByteArray(length)
            var cachedRow = -1
            fun row(index: Int): ByteArray {
                if (index != cachedRow) {
                    buffer.position(origin + index * stride)
                    buffer.get(bytes, 0, length)
                    cachedRow = index
                }
                return bytes
            }
        }
        val ys = Rows(y, width, height)
        val us = Rows(u, (width + 1) / 2, (height + 1) / 2)
        val vs = Rows(v, (width + 1) / 2, (height + 1) / 2)
        val result = ByteArray(outWidth * outHeight * 3)
        // Traverse sensor rows so each row is copied once, including rotation.
        val rows = if (rotate90) outWidth else outHeight
        val columns = if (rotate90) outHeight else outWidth
        for (r in 0 until rows) {
            val sy = if (rotate90) height - 1 - r * height / outWidth else r * height / outHeight
            val yr = ys.row(sy); val ur = us.row(sy / 2); val vr = vs.row(sy / 2)
            for (c in 0 until columns) {
                val sx = c * width / columns
                val index = (if (rotate90) c * outWidth + r else r * outWidth + c) * 3
                result[index] = yr[sx * ys.pixel]
                result[index + 1] = ur[sx / 2 * us.pixel]
                result[index + 2] = vr[sx / 2 * vs.pixel]
            }
        }
        return result
    }

    /** Inverse of the preview's clockwise sensor-to-upright rotation. */
    fun sensorMatteIndex(x: Int, y: Int, side: Int, rotate90: Boolean): Int =
        if (rotate90) x * side + (side - 1 - y) else y * side + x

    /** Camera streams use sensor timestamps; wall-clock arrival order is not synchronization. */
    fun closestDepth(frames: Collection<DepthFrame>, timestamp: Long): ByteArray? =
        frames.minByOrNull { abs(it.timestamp - timestamp) }
            ?.takeIf { abs(it.timestamp - timestamp) <= DEPTH_MAX_AGE_NS }?.bytes
}
