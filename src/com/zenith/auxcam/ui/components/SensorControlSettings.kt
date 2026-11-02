package com.zenith.auxcam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.CaptureMode
import com.zenith.auxcam.SensorControlPolicy
import com.zenith.auxcam.WhiteBalancePreset
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraType

/** Controls are backed by current-session metadata and real Camera2 requests. */
@Composable
fun SensorControlSettings(state: CameraUiState, actions: CameraActions) {
    Column {
        Text("Sensor controls", style = CameraType.Heading, color = CameraColors.Label)
        Text(state.sensorControlStatus, style = CameraType.Caption, color = CameraColors.LabelSecondary)
        Spacer(Modifier.height(10.dp))
        if (!state.canAdjustSensorControls) {
            Text("Available once the lens is ready and capture, recording or checks have finished.",
                style = CameraType.Caption, color = CameraColors.LabelSecondary)
            return@Column
        }
        if (state.manualExposureAvailable || state.manualExposure) {
            TogglePill(label = if (state.manualExposure) "MANUAL EXPOSURE · RESET TO AUTO" else "AUTO EXPOSURE · SET MANUALLY",
                active = state.manualExposure, onClick = actions::onManualExposureToggle,
                modifier = Modifier.fillMaxWidth())
        } else {
            Text("Manual exposure unavailable for this lens or resolution. Auto is retained.",
                style = CameraType.Caption, color = CameraColors.LabelSecondary)
        }
        if (state.manualExposure && state.manualExposureAvailable) {
            val caps = state.sensorControls
            Spacer(Modifier.height(12.dp))
            Text("ISO ${state.sensorIso}", style = CameraType.Value, color = CameraColors.Label)
            IosSlider(
                value = SensorControlPolicy.fraction(state.sensorIso.toLong(), caps.minIso.toLong(), caps.maxIso.toLong()),
                valueRange = 0f..1f, onValueChange = actions::onSensorIsoChanged)
            Text("SHUTTER · ${SensorControlPolicy.shutterLabel(state.sensorExposureNs)}",
                style = CameraType.Value, color = CameraColors.Label)
            IosSlider(
                value = SensorControlPolicy.fraction(state.sensorExposureNs, caps.minExposureNs, state.maxManualExposureNs),
                valueRange = 0f..1f, onValueChange = actions::onSensorShutterChanged)
            Text(if (state.mode == CaptureMode.Photo)
                "ISO and shutter are both manual. Photos use the selected time (up to 1 s). Preview exposure is capped for its frame rate and may appear darker than the photo. Flash and EV require Auto."
                else "ISO and shutter are both manual. Shutter time is limited to the selected video frame period. Flash and EV require Auto.",
                style = CameraType.Caption, color = CameraColors.LabelSecondary)
        }
        val presets = WhiteBalancePreset.entries.filter { state.sensorControls.supportsWhiteBalance(it.mode) }
        if (presets.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("WHITE BALANCE · ${state.whiteBalance.label}", style = CameraType.Section, color = CameraColors.LabelTertiary)
            Text("Camera-advertised presets. No uncalibrated Kelvin estimate.", style = CameraType.Caption, color = CameraColors.LabelSecondary)
            Spacer(Modifier.height(8.dp))
            for (group in presets.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (preset in group) TogglePill(label = preset.label, active = state.whiteBalance == preset,
                        onClick = { actions.onWhiteBalanceSelected(preset) }, modifier = Modifier.weight(1f))
                    if (group.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
