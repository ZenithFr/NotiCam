package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class CaptureOutputTest {
    @Test fun acceptsAdvertised50MpShapes() {
        assertTrue(CaptureOutput.is50MpSize(8160, 6144))
        assertTrue(CaptureOutput.is50MpSize(8192, 6144))
        assertTrue(CaptureOutput.is50MpSize(6120, 8160))
    }

    @Test fun rejectsBinnedOutputAndInvalidDimensions() {
        assertFalse(CaptureOutput.is50MpSize(4080, 3072))
        assertFalse(CaptureOutput.is50MpSize(0, 6144))
        assertFalse(CaptureOutput.is50MpSize(-8160, 6144))
        assertFalse(CaptureOutput.is50MpSize(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun rejectsMarketingSizedButCroppedFrames() {
        assertFalse(CaptureOutput.is50MpSize(10000, 5000))
    }

    @Test fun jpegMustMatchConfiguredPixelsNotJustMegapixelCount() {
        assertTrue(CaptureOutput.matches(8160, 6144, 8160, 6144))
        assertTrue(CaptureOutput.matches(6144, 8160, 8160, 6144))
        assertFalse(CaptureOutput.matches(8192, 6144, 8160, 6144))
        assertFalse(CaptureOutput.matches(4080, 3072, 8160, 6144))
    }

    @Test fun onlyPhotoAndVideoAreCaptureModes() {
        assertEquals(listOf(CaptureMode.Video, CaptureMode.Photo), CaptureMode.entries.toList())
    }

    @Test fun photoIsSharpByDefault() {
        assertFalse(CameraUiState().bokehActive)
        assertFalse(CameraUiState().canTakePhoto)
        assertTrue(CameraUiState(sessionReady = true).canTakePhoto)
    }

    @Test fun videoCannotTakeStillOrEnableBokeh() {
        val state = CameraUiState(mode = CaptureMode.Video, sessionReady = true, bokehEnabled = true)
        assertFalse(state.canTakePhoto)
        assertFalse(state.bokehActive)
    }

    @Test fun highResolutionCannotSilentlyApply12MpBokeh() {
        assertTrue(CameraUiState(bokehEnabled = true).bokehActive)
        assertFalse(CameraUiState(bokehEnabled = true, resolution = Resolution.High).bokehActive)
    }

    @Test fun captureBusyDisablesPhotoShutter() {
        assertFalse(CameraUiState(sessionReady = true, captureBusy = true).canTakePhoto)
    }
}
