package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class CameraZoomTest {
    @Test fun logicalStereoSessionUsesMainLensWithoutChangingPhysicalLenses() {
        assertEquals("0", CameraZoom.lensForSession("5"))
        assertEquals("0", CameraZoom.lensForSession(null))
        for (id in listOf("0", "1", "2", "3")) assertEquals(id, CameraZoom.lensForSession(id))
        assertEquals(1f, CameraZoom.digitalRatio(1f, CameraZoom.lensForSession("5"), 8f), 0f)
    }

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
        assertTrue(CameraUiState(mode = CaptureMode.Video, sessionReady = true, videoProfilesChecked = true,
            videoSupportedProfiles = setOf(VideoPolicy.profileKey(VideoPolicy.HD, 30))).canUseShutter)
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
    @Test fun widerLiveViewStartsAtMatchingCropAndEndsExactlyAtIdentity() {
        assertEquals(2f, CameraZoom.incomingPreviewScale(.5f, 0f, 1f), 0f)
        assertEquals(1f, CameraZoom.outgoingSnapshotScale(.5f, 0f, 1f), 0f)
        assertEquals(1.5f, CameraZoom.incomingPreviewScale(.5f, .5f, 1f), 0f)
        assertEquals(.75f, CameraZoom.outgoingSnapshotScale(.5f, .5f, 1f), 0f)
        assertEquals(1f, CameraZoom.incomingPreviewScale(.5f, 1f, 1f), 0f)
        assertEquals(.5f, CameraZoom.outgoingSnapshotScale(.5f, 1f, 1f), 0f)
    }
    @Test fun bothLayersShareAFieldOfViewThroughoutZoomOut() {
        for (ratio in listOf(.3f, .5f, .6f, .95f)) {
            var previous = Float.MAX_VALUE
            repeat(101) { step ->
                val progress = step / 100f
                val live = CameraZoom.incomingPreviewScale(ratio, progress, 1f)
                val old = CameraZoom.outgoingSnapshotScale(ratio, progress, 1f)
                assertTrue(live >= 1f && live <= previous)
                assertEquals(ratio, old / live, .00001f)
                previous = live
            }
            assertEquals(1f, previous, 0f)
        }
    }
    @Test fun reversedIntroStartsFromCurrentSnapshotScaleWithoutJumping() {
        assertEquals(1.4f, CameraZoom.outgoingSnapshotScale(.6f, 0f, 1.4f), .00001f)
        assertEquals(1.4f / .6f, CameraZoom.incomingPreviewScale(.6f, 0f, 1.4f), .00001f)
        assertEquals(1f, CameraZoom.incomingPreviewScale(.6f, 1f, 1.4f), 0f)
    }
    @Test fun zoomInNeverTemporarilyShrinksTheIncomingLiveView() {
        repeat(101) {
            assertEquals(1f, CameraZoom.incomingPreviewScale(2f, it / 100f, 1f), 0f)
        }
    }
    @Test fun handoffGeometryClampsProgressAndRejectsNonfiniteInputs() {
        assertEquals(2f, CameraZoom.incomingPreviewScale(.5f, -1f, 1f), 0f)
        assertEquals(1f, CameraZoom.incomingPreviewScale(.5f, 2f, 1f), 0f)
        assertEquals(1f, CameraZoom.incomingPreviewScale(Float.NaN, Float.NaN, Float.NaN), 0f)
        assertTrue(CameraZoom.incomingPreviewScale(Float.MIN_VALUE, 0f, Float.MAX_VALUE).isFinite())
    }

}
