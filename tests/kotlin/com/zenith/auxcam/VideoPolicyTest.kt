package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class VideoPolicyTest {
    @Test fun acceptsAlignedNativeAspectWithoutRequiringUnbinnedPixels() {
        assertTrue(VideoPolicy.matchesSensor(1440, 1080, 4080, 3072))
        assertTrue(VideoPolicy.matchesSensor(1920, 1440, 8000, 6000))
        assertTrue(VideoPolicy.matchesSensor(4000, 3000, 4000, 3000))
    }
    @Test fun rejectsWideCropOfFourThreeSensor() {
        assertFalse(VideoPolicy.matchesSensor(1920, 1080, 4080, 3072))
        assertFalse(VideoPolicy.matchesSensor(3840, 2160, 8000, 6000))
    }
    @Test fun respectsActualSensorAspectRatherThanHardcodingFourThree() {
        assertTrue(VideoPolicy.matchesSensor(1920, 1080, 3840, 2160))
        assertFalse(VideoPolicy.matchesSensor(1440, 1080, 3840, 2160))
        assertTrue(VideoPolicy.matchesSensor(2000, 2000, 4000, 4000))
    }
    @Test fun invalidDimensionsNeverMatch() {
        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            assertFalse(VideoPolicy.matchesSensor(bad, 1080, 4000, 3000))
            assertFalse(VideoPolicy.matchesSensor(1440, bad, 4000, 3000))
            assertFalse(VideoPolicy.matchesSensor(1440, 1080, bad, 3000))
            assertFalse(VideoPolicy.matchesSensor(1440, 1080, 4000, bad))
        }
    }
    @Test fun viewerUsesRotatedMetadataForNativeAndOlderWideVideos() {
        assertEquals(3f / 4f, VideoPolicy.displayAspect(1440, 1080, 90)!!, 0f)
        assertEquals(3f / 4f, VideoPolicy.displayAspect(1440, 1080, 270)!!, 0f)
        assertEquals(9f / 16f, VideoPolicy.displayAspect(1920, 1080, 90)!!, 0f)
        assertEquals(16f / 9f, VideoPolicy.displayAspect(1920, 1080, 0)!!, 0f)
        assertEquals(4f / 3f, VideoPolicy.displayAspect(1440, 1080, 180)!!, 0f)
        assertEquals(3f / 4f, VideoPolicy.displayAspect(1440, 1080, -90)!!, 0f)
    }
    @Test fun viewerRejectsMissingMetadataRatherThanDividingByZero() {
        assertNull(VideoPolicy.displayAspect(0, 1080, 90))
        assertNull(VideoPolicy.displayAspect(1440, -1, 0))
    }
    @Test fun bitrateScalesWithEncodedPixelsRatherThanOnlyWidth() {
        assertTrue(VideoPolicy.bitrate(1920, 1440) > VideoPolicy.bitrate(1920, 1080))
        assertEquals(VideoPolicy.bitrate(1440, 1080), VideoPolicy.bitrate(1080, 1440))
        assertEquals(21_772_800, VideoPolicy.bitrate(1920, 1080))
    }
    @Test fun bitrateIsBoundedAndCannotOverflowForLargeSensorOutputs() {
        assertEquals(12_000_000, VideoPolicy.bitrate(320, 240))
        assertEquals(120_000_000, VideoPolicy.bitrate(8000, 6000))
        assertEquals(120_000_000, VideoPolicy.bitrate(Int.MAX_VALUE, Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { VideoPolicy.bitrate(0, 1080) }
    }
}
