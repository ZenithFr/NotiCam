package com.zenith.auxcam

import org.junit.Assert.*
import org.junit.Test

class VideoSelectionTest {
    @Test fun requestedQualityRateMatrixIsExact() {
        for (fps in listOf(24, 30, 60)) assertTrue(VideoPolicy.allowedRate(VideoPolicy.HD, fps))
        for (fps in listOf(24, 30)) assertTrue(VideoPolicy.allowedRate(VideoPolicy.UHD, fps))
        assertFalse(VideoPolicy.allowedRate(VideoPolicy.UHD, 60))
        for (fps in listOf(-1, 0, 25, 29, 120)) {
            assertFalse(VideoPolicy.allowedRate(VideoPolicy.HD, fps))
            assertFalse(VideoPolicy.allowedRate(VideoPolicy.UHD, fps))
        }
        assertFalse(VideoPolicy.allowedRate(999, 30))
    }
    @Test fun qualityNeverMeansUpscaleOrWrongFourKBadge() {
        assertTrue(VideoPolicy.matchesQuality(1440, 1080, VideoPolicy.HD))
        assertTrue(VideoPolicy.matchesQuality(1920, 1080, VideoPolicy.HD))
        assertTrue(VideoPolicy.matchesQuality(3840, 2880, VideoPolicy.UHD))
        assertTrue(VideoPolicy.matchesQuality(3840, 2160, VideoPolicy.UHD))
        assertFalse(VideoPolicy.matchesQuality(2880, 2160, VideoPolicy.UHD))
        assertFalse(VideoPolicy.matchesQuality(1280, 720, VideoPolicy.HD))
        assertFalse(VideoPolicy.matchesQuality(0, 1080, VideoPolicy.HD))
        assertFalse(VideoPolicy.matchesSensor(3840, 2160, 4000, 3000))
        assertTrue(VideoPolicy.matchesSensor(3840, 2880, 4000, 3000))
    }
    @Test fun variableRangesDoNotProveRequestedConstantCaptureRate() {
        assertTrue(VideoPolicy.fixedRate(24, 24, 24))
        assertTrue(VideoPolicy.fixedRate(60, 60, 60))
        assertFalse(VideoPolicy.fixedRate(15, 30, 24))
        assertFalse(VideoPolicy.fixedRate(30, 60, 60))
        assertFalse(VideoPolicy.fixedRate(30, 30, 60))
    }
    @Test fun videoShutterRequiresCompletedProfileProbeAndExactSelection() {
        val video = CameraUiState(mode = CaptureMode.Video, sessionReady = true)
        assertFalse(video.canUseShutter)
        assertFalse(video.copy(videoProfilesChecked = true).canUseShutter)
        val supported = setOf(VideoPolicy.profileKey(VideoPolicy.HD, 30))
        val checked = video.copy(videoProfilesChecked = true, videoSupportedProfiles = supported)
        assertTrue(checked.canUseShutter)
        assertFalse(checked.copy(videoTargetFps = 60).canUseShutter)
        assertFalse(checked.copy(videoQuality = VideoPolicy.UHD).canUseShutter)
        assertFalse(checked.copy(captureBusy = true).canUseShutter)
        assertFalse(checked.copy(sessionReady = false).canUseShutter)
        assertTrue(video.copy(videoRecording = true).canUseShutter) // Stop must never depend on probes.
        assertTrue(CameraUiState(sessionReady = true).canTakePhoto)
    }
    @Test fun profileKeysCannotAliasAcrossRequestedChoices() {
        val keys = listOf(1080 to 24, 1080 to 30, 1080 to 60, 2160 to 24, 2160 to 30)
            .map { VideoPolicy.profileKey(it.first, it.second) }
        assertEquals(5, keys.toSet().size)
    }
    @Test fun bitrateFollowsSelectedCadenceAndRejectsInvalidRate() {
        assertTrue(VideoPolicy.bitrate(3840, 2880, 30) > VideoPolicy.bitrate(3840, 2880, 24))
        assertTrue(VideoPolicy.bitrate(1920, 1080, 60) > VideoPolicy.bitrate(1920, 1080, 30))
        assertThrows(IllegalArgumentException::class.java) { VideoPolicy.bitrate(1920, 1080, 0) }
    }
}
