package com.zenith.auxcam.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.VideoPolicy
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraType

/** Independent resolution and frame-rate choices. Unsupported profiles stay
 * disabled rather than silently cropping, upscaling or falling back to 30 fps. */
@Composable
fun VideoProfileSettings(state: CameraUiState, actions: CameraActions) {
    val editable = state.sessionReady && !state.captureBusy && !state.videoStarting && !state.videoRecording &&
        !state.previewTransitioning && !state.manualExposure
    Column {
        SettingsChoiceRow(
            icon = NotiIcons.Aspect,
            title = "Resolution",
            subtitle = "Native full-field frame · no crop or upscale",
            options = listOf("1080p", "4K"),
            selectedIndex = if (state.videoQuality == VideoPolicy.UHD) 1 else 0,
            onSelect = { actions.onVideoQualitySelected(if (it == 1) VideoPolicy.UHD else VideoPolicy.HD) },
            enabled = editable,
        )
        SettingsDivider()
        val rates = if (state.videoQuality == VideoPolicy.HD) listOf(24, 30, 60) else listOf(24, 30)
        SettingsChoiceRow(
            icon = NotiIcons.FrameRate,
            title = "Frame rate",
            subtitle = if (!state.videoProfilesChecked) "Checking camera and encoder profiles…"
                else "Unavailable rates are disabled",
            options = rates.map { "$it fps" },
            selectedIndex = rates.indexOf(state.videoTargetFps).coerceAtLeast(0),
            onSelect = { actions.onVideoRateSelected(rates[it]) },
            enabled = editable,
            optionEnabled = { index ->
                state.videoProfilesChecked &&
                    VideoPolicy.profileKey(state.videoQuality, rates[index]) in state.videoSupportedProfiles
            },
        )
        Text(
            "H.264 MP4 with AAC audio. 4K may be unavailable on this lens; native framing is retained.",
            style = CameraType.Caption, color = CameraColors.LabelTertiary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        )
        Text(
            state.status, style = CameraType.Caption, color = CameraColors.LabelSecondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}
