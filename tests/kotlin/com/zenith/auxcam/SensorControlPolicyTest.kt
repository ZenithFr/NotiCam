package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class SensorControlPolicyTest {
    private val caps = SensorControlCapabilities(true, 50, 6400, 100_000L, 4_000_000_000L, 5_000_000_000L, 0x62)
    @Test fun wireIsVersionedAndNoMissingMetadataInventsManualSupport() {
        assertEquals(caps, SensorControlCapabilities.fromWire(caps.toWire()))
        assertFalse(SensorControlCapabilities.fromWire(null).hasManualExposure)
        assertFalse(SensorControlCapabilities.fromWire(longArrayOf(1)).hasManualExposure)
        assertFalse(SensorControlCapabilities.fromWire(caps.toWire().also { it[0] = 2 }).hasManualExposure)
        assertFalse(SensorControlCapabilities.fromWire(caps.toWire().also { it[2] = Long.MAX_VALUE }).hasManualExposure)
        assertFalse(SensorControlCapabilities.fromWire(caps.toWire().also { it[7] = -1 }).hasManualExposure)
        assertFalse(caps.copy(manualSensor = false).hasManualExposure)
        assertFalse(caps.copy(minIso = 0).hasManualExposure)
        assertFalse(caps.copy(maxIso = 20).hasManualExposure)
        assertFalse(caps.copy(minExposureNs = 0).hasManualExposure)
        assertFalse(caps.copy(maxFrameDurationNs = 1).hasManualExposure)
    }
    @Test fun shutterAndIsoClampToSensorRangesAndWatchdogBudget() {
        assertEquals(50, SensorControlPolicy.iso(-1, caps))
        assertEquals(6400, SensorControlPolicy.iso(Int.MAX_VALUE, caps))
        assertEquals(100_000L, SensorControlPolicy.exposure(-1L, caps, false, 30))
        assertEquals(1_000_000_000L, SensorControlPolicy.exposure(Long.MAX_VALUE, caps, false, 30))
        assertEquals(0L, SensorControlPolicy.exposure(1L, SensorControlCapabilities(), false, 30))
    }
    @Test fun previewAndVideoUseFramePeriodWhileStillRetainsSelectedTime() {
        assertEquals(500_000_000L, SensorControlPolicy.exposure(500_000_000L, caps, false, 60))
        assertEquals(16_666_666L, SensorControlPolicy.liveExposure(500_000_000L, caps, 60))
        assertEquals(33_333_333L, SensorControlPolicy.exposure(500_000_000L, caps, true, 30))
        assertEquals(16_666_666L, SensorControlPolicy.frameDuration(1_000_000L, caps, 60))
        assertEquals(500_000_000L, SensorControlPolicy.frameDuration(500_000_000L, caps, 60))
        assertEquals(0L, SensorControlPolicy.maxExposure(caps.copy(minExposureNs = 40_000_000L), true, 30))
    }
    @Test fun whiteBalanceShowsOnlyAdvertisedStandardPresetsNotOffOrKelvinGuesses() {
        assertTrue(caps.supportsWhiteBalance(1))
        assertTrue(caps.supportsWhiteBalance(5))
        assertTrue(caps.supportsWhiteBalance(6))
        assertFalse(caps.supportsWhiteBalance(2))
        assertFalse(caps.supportsWhiteBalance(0))
        assertFalse(caps.supportsWhiteBalance(64))
        assertFalse(SensorControlCapabilities().supportsWhiteBalance(1))
        assertEquals(WhiteBalancePreset.Auto, WhiteBalancePreset.fromMode(0))
        assertEquals(WhiteBalancePreset.Daylight, WhiteBalancePreset.fromMode(5))
    }
    @Test fun logarithmicSlidersAreBoundedMonotonicAndRoundTrip() {
        var previous = 0L
        for (i in 0..100) {
            val f = i / 100f
            val value = SensorControlPolicy.fromFraction(f, 100_000L, 1_000_000_000L)
            assertTrue(value >= previous)
            assertEquals(f, SensorControlPolicy.fraction(value, 100_000L, 1_000_000_000L), .0001f)
            previous = value
        }
        assertEquals(50L, SensorControlPolicy.fromFraction(Float.NaN, 50, 6400))
        assertEquals(50L, SensorControlPolicy.fromFraction(-5f, 50, 6400))
        assertEquals(6400L, SensorControlPolicy.fromFraction(5f, 50, 6400))
        assertEquals(0L, SensorControlPolicy.fromFraction(.5f, 0, 100))
        assertEquals(50L, SensorControlPolicy.fromFraction(.5f, 50, 50))
    }
    @Test fun labelsUseActualRequestedDuration() {
        assertEquals("1/125 s", SensorControlPolicy.shutterLabel(8_000_000L))
        assertEquals("0.50 s", SensorControlPolicy.shutterLabel(500_000_000L))
        assertEquals("AUTO", SensorControlPolicy.shutterLabel(0L))
    }
    @Test fun adjustmentSafetyDoesNotChangeShutterReadiness() {
        val ui = CameraUiState(sessionReady = true, sensorControlsLoaded = true, sensorControls = caps)
        assertTrue(ui.canAdjustSensorControls)
        assertTrue(ui.manualExposureAvailable)
        assertTrue(ui.copy(manualExposure = true).canTakePhoto)
        assertFalse(ui.copy(sensorControlsLoaded = false).canAdjustSensorControls)
        assertFalse(ui.copy(captureBusy = true).canAdjustSensorControls)
        assertFalse(ui.copy(videoRecording = true).canAdjustSensorControls)
        assertFalse(ui.copy(zoomBlendPending = true).canAdjustSensorControls)
        assertFalse(ui.copy(bokehChecksRunning = true).canAdjustSensorControls)
        assertFalse(ui.copy(resolution = Resolution.High).manualExposureAvailable)
    }
}
