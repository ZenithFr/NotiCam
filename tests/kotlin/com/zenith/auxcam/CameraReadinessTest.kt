package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class CameraReadinessTest {
    @Test fun portraitShutterDoesNotDependOnLiveMaskFreshnessOrModelFailure() {
        val ready = CameraUiState(sessionReady = true, bokehEnabled = true)
        assertFalse(ready.bokehActive)
        assertTrue(ready.canTakePhoto)
        assertTrue(ready.copy(bokehModelReady = true).canTakePhoto)
        assertTrue(ready.copy(bokehModelReady = false, bokehLiveSuspended = true).canTakePhoto)
        assertTrue(ready.copy(bokehModelError = "model could not load").canTakePhoto)
        assertTrue(ready.copy(bokehModelError = "failure").canUseShutter)
    }
    @Test fun genuineCameraAndCaptureSafetyGatesRemain() {
        val ready = CameraUiState(sessionReady = true, bokehEnabled = true)
        assertFalse(ready.copy(sessionReady = false).canTakePhoto)
        assertFalse(ready.copy(captureBusy = true).canTakePhoto)
        assertFalse(ready.copy(zoomBlendPending = true).canTakePhoto)
        assertFalse(ready.copy(bokehChecksRunning = true).canTakePhoto)
        assertFalse(ready.copy(mode = CaptureMode.Video).canTakePhoto)
    }
}
