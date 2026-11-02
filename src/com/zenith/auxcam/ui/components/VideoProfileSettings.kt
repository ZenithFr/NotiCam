package com.zenith.auxcam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.VideoPolicy
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraType

@Composable
fun VideoProfileSettings(state: CameraUiState, actions: CameraActions) {
    val editable = state.sessionReady && !state.captureBusy && !state.videoStarting && !state.videoRecording &&
        !state.previewTransitioning && !state.manualExposure
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (quality in listOf(VideoPolicy.HD, VideoPolicy.UHD)) {
                TogglePill(if (quality == VideoPolicy.HD) "1080" else "4K", state.videoQuality == quality,
                    { actions.onVideoQualitySelected(quality) }, Modifier.weight(1f), enabled = editable)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (fps in if (state.videoQuality == VideoPolicy.HD) listOf(24, 30, 60) else listOf(24, 30)) {
                TogglePill("$fps fps", state.videoTargetFps == fps, { actions.onVideoRateSelected(fps) }, Modifier.weight(1f),
                    enabled = editable && state.videoProfilesChecked &&
                        VideoPolicy.profileKey(state.videoQuality, fps) in state.videoSupportedProfiles)
            }
        }
        Text(if (!state.videoProfilesChecked) "Checking camera and encoder profiles…" else
            "Unavailable rates are disabled. Native/full-field framing is retained: 1080-line or genuine 3840-wide 4K output. No crop, upscale, overclock or 30 fps encoder fallback. 4K may be unavailable on this lens.",
            style = CameraType.Caption, color = CameraColors.LabelSecondary)
        Text(state.status, style = CameraType.Caption, color = CameraColors.Label)
    }
}
