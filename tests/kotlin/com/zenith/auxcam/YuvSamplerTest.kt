package com.zenith.auxcam

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class YuvSamplerTest {
    private fun plane(w: Int, h: Int, pixel: Int, padding: Int, direct: Boolean, offset: Int, seed: Int): FrameData.Plane {
        val row = (w - 1) * pixel + 1
        val stride = row + padding
        // Last row deliberately omits all trailing padding, as Camera2 can do.
        val size = offset + (h - 1) * stride + row
        val buffer = if (direct) ByteBuffer.allocateDirect(size) else ByteBuffer.allocate(size)
        for (i in 0 until size) buffer.put(i, ((i * 37 + seed) % 256).toByte())
        buffer.position(offset)
        return FrameData.Plane(buffer, stride, pixel)
    }
    // Independent reference: the original per-pixel mapping used by BokehInput.copy.
    private fun reference(planes: List<FrameData.Plane>, w: Int, h: Int, ow: Int, oh: Int, rotate: Boolean): ByteArray {
        val result = ByteArray(ow * oh * 3)
        var index = 0
        for (dy in 0 until oh) for (dx in 0 until ow) {
            val x = if (rotate) dy * w / oh else dx * w / ow
            val y = if (rotate) h - 1 - dx * h / ow else dy * h / oh
            for ((i, p) in planes.withIndex()) {
                val sx = if (i == 0) x else x / 2
                val sy = if (i == 0) y else y / 2
                result[index++] = p.buffer.get(p.buffer.position() + sy * p.rowStride + sx * p.pixelStride)
            }
        }
        return result
    }
    @Test fun bulkMappingMatchesReferenceAcrossGeometryStridesOriginsAndRotation() {
        for ((w, h) in listOf(1 to 1, 7 to 5, 8 to 6, 17 to 13, 1440 to 1080))
            for (direct in listOf(false, true)) for (rotate in listOf(false, true))
                for (chromaStride in listOf(1, 2)) {
                    val planes = listOf(plane(w, h, 1, 7, direct, 3, 1),
                        plane((w + 1) / 2, (h + 1) / 2, chromaStride, 5, direct, 2, 77),
                        plane((w + 1) / 2, (h + 1) / 2, chromaStride, 9, direct, 1, 145))
                    for ((ow, oh) in listOf(3 to 4, 384 to 512)) {
                        val positions = planes.map { it.buffer.position() }
                        val limits = planes.map { it.buffer.limit() }
                        assertArrayEquals("$w x $h, direct=$direct rotate=$rotate pixel=$chromaStride",
                            reference(planes, w, h, ow, oh, rotate),
                            FrameData.sampleYuv(planes[0], planes[1], planes[2], w, h, ow, oh, rotate))
                        assertEquals(positions, planes.map { it.buffer.position() })
                        assertEquals(limits, planes.map { it.buffer.limit() })
                    }
                }
    }
    @Test fun supportsIndependentChromaStridesReadOnlySlicesAndLumaPixelStride() {
        val y = plane(7, 5, 2, 0, true, 4, 11)
        val u = plane(4, 3, 1, 1, true, 3, 33)
        val originalV = plane(4, 3, 3, 2, true, 2, 55)
        val v = originalV.copy(buffer = originalV.buffer.slice().asReadOnlyBuffer())
        for (rotate in listOf(false, true)) assertArrayEquals(reference(listOf(y,u,v), 7, 5, 384, 512, rotate),
            FrameData.sampleYuv(y, u, v, 7, 5, 384, 512, rotate))
    }
    @Test fun rejectsTruncationAndOverlappingRowsRatherThanReadingPadding() {
        val y = plane(7, 5, 1, 0, false, 2, 0)
        val u = plane(4, 3, 2, 0, false, 0, 0)
        val v = plane(4, 3, 2, 0, false, 0, 0)
        assertThrows(IllegalArgumentException::class.java) {
            FrameData.sampleYuv(y.copy(rowStride = 6), u, v, 7, 5, 384, 512, true)
        }
        y.buffer.limit(y.buffer.limit() - 1)
        assertThrows(IllegalArgumentException::class.java) { FrameData.sampleYuv(y, u, v, 7, 5, 384, 512, true) }
        assertThrows(IllegalArgumentException::class.java) { FrameData.sampleYuv(y, u, v, 0, 5, 384, 512, false) }
    }
}
