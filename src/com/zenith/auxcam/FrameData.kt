package com.zenith.auxcam

import java.nio.ByteBuffer
import kotlin.math.abs

/** Platform-independent buffer rules shared by preview and capture. */
internal object FrameData {
    const val DEPTH_MAX_AGE_NS = 100_000_000L
    const val MATTE_MAX_AGE_NS = 500_000_000L

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

    /** Inverse of the preview's clockwise sensor-to-upright rotation. */
    fun sensorMatteIndex(x: Int, y: Int, side: Int, rotate90: Boolean): Int =
        if (rotate90) x * side + (side - 1 - y) else y * side + x

    /** Camera streams use sensor timestamps; wall-clock arrival order is not synchronization. */
    fun closestDepth(frames: Collection<DepthFrame>, timestamp: Long): ByteArray? =
        frames.minByOrNull { abs(it.timestamp - timestamp) }
            ?.takeIf { abs(it.timestamp - timestamp) <= DEPTH_MAX_AGE_NS }?.bytes
}
