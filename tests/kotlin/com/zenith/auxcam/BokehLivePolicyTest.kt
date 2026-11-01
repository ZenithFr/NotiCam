package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class BokehLivePolicyTest {
    @Test fun coldLoadIsNotAReasonToSuspend() {
        assertEquals(0, BokehLivePolicy.nextSlowCount(0, false, 900.0))
        // The user's RVM cold 448ms + warm 194ms must NOT disable the effect.
        assertEquals(0, BokehLivePolicy.nextSlowCount(0, true, 194.4))
    }
    @Test fun requiresTwoConsecutiveSlowWarmCyclesAndResetsAfterRecovery() {
        val first = BokehLivePolicy.nextSlowCount(0, true, 707.9)
        assertEquals(1, first)
        assertEquals(2, BokehLivePolicy.nextSlowCount(first, true, 850.2))
        assertEquals(2, BokehLivePolicy.nextSlowCount(2, true, 850.2))
        assertEquals(0, BokehLivePolicy.nextSlowCount(first, true, 220.0))
        assertEquals(1, BokehLivePolicy.nextSlowCount(0, true, 220.1))
        assertEquals(0, BokehLivePolicy.nextSlowCount(first, true, Double.NaN))
    }
    @Test fun cadenceLeavesPreviewHeadroomWithoutReducingMaskResolution() {
        assertEquals(99L, BokehLivePolicy.intervalMs(39.3))
        assertEquals(254L, BokehLivePolicy.intervalMs(194.4))
        assertEquals(300L, BokehLivePolicy.intervalMs(707.9))
        for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 0.0))
            assertEquals(83L, BokehLivePolicy.intervalMs(invalid))
    }
    @Test fun atomicSnapshotCannotCrossSessionsModelsOrFreshnessBoundary() {
        val bytes = ByteArray(256 * 256)
        val stamp = 1_000_000_000L
        val matte = BokehLivePolicy.Matte(bytes, stamp, 4, 7)
        assertSame(bytes, matte.forFrame(stamp, 4, 7))
        assertSame(bytes, matte.forFrame(stamp + 500_000_000L, 4, 7))
        assertSame(bytes, matte.forFrame(stamp - 500_000_000L, 4, 7))
        assertNull(matte.forFrame(stamp + 500_000_001L, 4, 7))
        assertNull(matte.forFrame(stamp, 5, 7))
        assertNull(matte.forFrame(stamp, 4, 8))
        assertNull(matte.forFrame(0, 4, 7))
        assertThrows(IllegalArgumentException::class.java) { BokehLivePolicy.Matte(ByteArray(1), stamp, 4, 7) }
    }
    @Test fun freshnessRejectsInvalidClocksAndSupportsLargeSensorTimestamps() {
        assertFalse(FrameData.isFresh(0, 1, 500))
        assertFalse(FrameData.isFresh(1, -1, 500))
        assertFalse(FrameData.isFresh(1, 1, -1))
        assertTrue(FrameData.isFresh(Long.MAX_VALUE - 2, Long.MAX_VALUE, 2))
        assertFalse(FrameData.isFresh(1, Long.MAX_VALUE, 500))
    }
}
