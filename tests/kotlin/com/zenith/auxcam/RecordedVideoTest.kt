package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class RecordedVideoTest {
    @Test fun rejectsSilentResolutionSubstitutionAndMissingDimensions() {
        assertFalse(VideoPolicy.matchesRecordedOutput(3840, 2880, 30, 1440, 1080, 30.0))
        assertFalse(VideoPolicy.matchesRecordedOutput(1440, 1080, 30, 0, 0, 30.0))
        assertFalse(VideoPolicy.matchesRecordedOutput(0, 0, 30, 0, 0, null))
    }
    @Test fun retainsLegitimateRotatedMetadataAndUnknownOptionalRate() {
        assertTrue(VideoPolicy.matchesRecordedOutput(1440, 1080, 30, 1440, 1080, null))
        assertTrue(VideoPolicy.matchesRecordedOutput(1440, 1080, 30, 1080, 1440, 30.0))
    }
    @Test fun rejectsContradictoryCaptureRateWithoutDemandingPerfectDecimalRounding() {
        assertFalse(VideoPolicy.matchesRecordedOutput(1440, 1080, 60, 1440, 1080, 30.0))
        assertTrue(VideoPolicy.matchesRecordedOutput(1440, 1080, 60, 1440, 1080, 59.94))
        assertTrue(VideoPolicy.matchesRecordedOutput(1440, 1080, 24, 1440, 1080, 23.976))
    }
    @Test fun rejectsInvalidRateMetadataAndUnrequestedRates() {
        for (rate in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0))
            assertFalse(VideoPolicy.matchesRecordedOutput(1440, 1080, 30, 1440, 1080, rate))
        assertFalse(VideoPolicy.matchesRecordedOutput(1440, 1080, 120, 1440, 1080, 120.0))
    }
}
