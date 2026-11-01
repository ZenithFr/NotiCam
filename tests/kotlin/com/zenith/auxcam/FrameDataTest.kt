package com.zenith.auxcam

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FrameDataTest {
    @Test fun packsPaddedRowsWithoutReadingTrailingPadding() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 99, 99, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), FrameData.packPlane(source, 2, 2, 4, 1))
        assertEquals(0, source.position())
    }

    @Test fun honorsPositionAndPixelStride() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 1, 99, 2, 99, 3, 99, 4))
        source.position(1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), FrameData.packPlane(source, 2, 2, 4, 2))
        assertEquals(1, source.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPlanes() {
        FrameData.packPlane(ByteBuffer.allocate(5), 2, 2, 4, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlappingRows() {
        FrameData.packPlane(ByteBuffer.allocate(8), 3, 2, 2, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDimensions() {
        FrameData.packPlane(ByteBuffer.allocate(8), 0, 2, 4, 1)
    }

    @Test fun choosesNearestTimestampNotMostRecentArrival() {
        val near = byteArrayOf(1)
        val late = byteArrayOf(2)
        val frames = listOf(FrameData.DepthFrame(100_000_000L, near), FrameData.DepthFrame(180_000_000L, late))
        assertSame(near, FrameData.closestDepth(frames, 110_000_000L))
        assertSame(late, FrameData.closestDepth(frames, 175_000_000L))
    }

    @Test fun rejectsStaleAndEmptyDepth() {
        assertNull(FrameData.closestDepth(emptyList(), 1L))
        assertNull(FrameData.closestDepth(listOf(FrameData.DepthFrame(1L, byteArrayOf(1))), 200_000_000L))
    }

    @Test fun rotatesUprightMatteBackToSensor() {
        val upright = byteArrayOf(1, 2, 3, 4)
        val sensor = ByteArray(4) { i -> upright[FrameData.sensorMatteIndex(i % 2, i / 2, 2, true)] }
        assertArrayEquals(byteArrayOf(2, 4, 1, 3), sensor)
        assertEquals(2, FrameData.sensorMatteIndex(0, 1, 2, false))
    }

    @Test fun acceptsBoundaryAge() {
        val data = byteArrayOf(1)
        val frames = listOf(FrameData.DepthFrame(1L, data))
        assertSame(data, FrameData.closestDepth(frames, FrameData.DEPTH_MAX_AGE_NS + 1))
        assertNull(FrameData.closestDepth(frames, FrameData.DEPTH_MAX_AGE_NS + 2))
    }
}
