package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class CameraZoomTest {
    @Test fun twoTimesAlwaysSelectsOpticalTelephoto() {
        assertEquals("3", CameraZoom.lensForZoom(2f))
        assertEquals("0", CameraZoom.lensForZoom(1f))
        assertEquals("2", CameraZoom.lensForZoom(.6f))
    }
    @Test fun twoTimesOnTeleIsFullSensorNotAnotherTwoTimesCrop() {
        assertEquals(1f, CameraZoom.digitalRatio(2f, "3", 10f), 0f)
        assertEquals(1f, CameraZoom.digitalRatio(.6f, "2", 10f), 0f)
        assertEquals(1f, CameraZoom.digitalRatio(1f, "0", 10f), 0f)
    }
    @Test fun additionalDigitalZoomIsRelativeToSelectedLens() {
        assertEquals(1.5f, CameraZoom.digitalRatio(3f, "3", 10f), .0001f)
        assertEquals(2f, CameraZoom.digitalRatio(2f, "0", 10f), .0001f)
        assertEquals(3f, CameraZoom.digitalRatio(100f, "3", 3f), .0001f)
    }
    @Test fun invalidInputsCannotCreateAnEmptyCrop() {
        assertEquals(1f, CameraZoom.digitalRatio(Float.NaN, "3", 10f), 0f)
        assertEquals(1f, CameraZoom.digitalRatio(-2f, "3", 10f), 0f)
        assertEquals("0", CameraZoom.lensForZoom(Float.NaN))
    }
    @Test fun videoAndPhotoHaveSeparateShutterPolicies() {
        assertTrue(CameraUiState(mode = CaptureMode.Video, sessionReady = true).canUseShutter)
        assertFalse(CameraUiState(mode = CaptureMode.Video, sessionReady = true).canTakePhoto)
        assertFalse(CameraUiState(mode = CaptureMode.Video, videoStarting = true, captureBusy = true).canUseShutter)
        assertTrue(CameraUiState(mode = CaptureMode.Video, videoRecording = true).canUseShutter)
    }
    @Test fun ultrawideCropsContinuouslyAllTheWayToOneTimes() {
        assertEquals("2", CameraZoom.lensForZoom(.95f))
        assertEquals("2", CameraZoom.lensForZoom(.9999f))
        assertEquals("0", CameraZoom.lensForZoom(1f))
        assertEquals(.95f / .6f, CameraZoom.digitalRatio(.95f, "2", 10f), .0001f)
    }
    @Test fun previewEasingConvergesWithoutOvershoot() {
        var zoom = 1f
        repeat(60) {
            val next = CameraZoom.smoothPreviewZoom(zoom, 2f, 16)
            assertTrue(next >= zoom && next <= 2f)
            zoom = next
        }
        assertEquals(2f, zoom, 0f)
    }
    @Test fun previewEasingRetargetsWithoutJumpingToNewTarget() {
        val rising = CameraZoom.smoothPreviewZoom(1f, 2f, 16)
        val falling = CameraZoom.smoothPreviewZoom(rising, .6f, 16)
        assertTrue(falling < rising && falling > .6f)
    }
    @Test fun previewEasingDependsOnTimeNotCallbackCount() {
        val twice = CameraZoom.smoothPreviewZoom(CameraZoom.smoothPreviewZoom(1f, 2f, 16), 2f, 16)
        val once = CameraZoom.smoothPreviewZoom(1f, 2f, 32)
        assertEquals(once, twice, .00001f)
    }
    @Test fun previewEasingRejectsInvalidInputs() {
        assertEquals(1f, CameraZoom.smoothPreviewZoom(Float.NaN, Float.NaN, 16), 0f)
        assertEquals(1.5f, CameraZoom.smoothPreviewZoom(1.5f, Float.NaN, 16), 0f)
        assertEquals(2f, CameraZoom.smoothPreviewZoom(0f, 2f, 16), 0f)
        assertTrue(CameraZoom.smoothPreviewZoom(1f, 2f, Long.MAX_VALUE) <= 2f)
    }
    @Test fun shutterWaitsForPreviewBlendButVideoStopNeverDoes() {
        assertFalse(CameraUiState(sessionReady = true, zoomBlendPending = true).canTakePhoto)
        assertFalse(CameraUiState(mode = CaptureMode.Video, sessionReady = true, zoomBlendPending = true).canUseShutter)
        assertTrue(CameraUiState(mode = CaptureMode.Video, videoRecording = true, zoomBlendPending = true).canUseShutter)
    }
}
