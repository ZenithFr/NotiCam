package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

/** Exercises production eligibility/defaults, not a successful Camera2 session. */
class RawCaptureStateTest {
    private val raw = CameraUiState(sessionReady = true, raw = true, rawCapabilityChecked = true,
        rawSensorWidth = 4080, rawSensorHeight = 3072)

    @Test fun singleFrameIsDefaultAndCountsFollowSharedPayloadPolicy() {
        assertEquals(1, raw.rawFrames)
        assertEquals(4, raw.maxRawFrames)
        assertTrue(raw.supportsRaw)
        assertTrue(raw.canTakePhoto)
        assertTrue(raw.canUseShutter)
    }
    @Test fun rawShutterWaitsForTheCurrentCapabilityCheck() {
        assertFalse(raw.copy(rawCapabilityChecked = false).supportsRaw)
        assertFalse(raw.copy(rawCapabilityChecked = false).canTakePhoto)
        assertFalse(raw.copy(rawSensorWidth = 0).canTakePhoto)
        assertFalse(raw.copy(rawSensorHeight = -1).canTakePhoto)
    }
    @Test fun largeOrInvalidDimensionsNeverPretendOneFrameIsSafe() {
        val huge = raw.copy(rawSensorWidth = 8193, rawSensorHeight = 8192)
        assertEquals(0, huge.maxRawFrames)
        assertFalse(huge.supportsRaw)
        assertFalse(huge.canUseShutter)
        assertEquals(0, raw.copy(rawSensorWidth = Int.MAX_VALUE, rawSensorHeight = Int.MAX_VALUE).maxRawFrames)
    }
    @Test fun highPixelCountDoesNotPretendFourRawFramesFit() {
        val large = raw.copy(rawSensorWidth = 8160, rawSensorHeight = 6144)
        assertEquals(1, large.maxRawFrames)
        assertTrue(large.supportsRaw)
        assertTrue(large.canUseShutter)
    }
    @Test fun jpegEligibilityAndExistingCaptureGatesRemainIndependent() {
        assertTrue(CameraUiState(sessionReady = true).canTakePhoto)
        assertTrue(raw.copy(raw = false, rawCapabilityChecked = false, rawSensorWidth = 0).canTakePhoto)
        assertFalse(raw.copy(captureBusy = true).canTakePhoto)
        assertFalse(raw.copy(zoomBlendPending = true).canTakePhoto)
        assertFalse(raw.copy(bokehChecksRunning = true).canTakePhoto)
        assertFalse(raw.copy(sessionReady = false).canTakePhoto)
        assertFalse(raw.copy(mode = CaptureMode.Video).canTakePhoto)
    }
}
