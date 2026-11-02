package com.zenith.auxcam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.BokehModel
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraSpacing
import com.zenith.auxcam.ui.theme.CameraType

@Composable
fun BokehModelSettings(state: CameraUiState, actions: CameraActions) {
    val enabled = !state.captureBusy && !state.bokehChecksRunning && !state.videoStarting && !state.videoRecording && !state.previewTransitioning
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Portrait", style = CameraType.Heading, color = CameraColors.Label)
        Text("Two choices. One shared portrait button. Ordinary JPEG and full-resolution capture stay unchanged.",
            style = CameraType.Caption, color = CameraColors.LabelSecondary)
        BokehModel.entries.forEach { model ->
            val chosen = state.bokehModel == model
            Column(Modifier.fillMaxWidth().heightIn(min = CameraSpacing.touch).clip(RoundedCornerShape(CameraSpacing.controlRadius))
                .background(if (chosen) CameraColors.Label else CameraColors.Matte)
                .semantics { selected = chosen }
                .clickable(enabled = enabled, role = Role.RadioButton) { actions.onBokehModelSelected(model) }
                .padding(16.dp)) {
                Text(model.label, style = CameraType.Value, color = if (chosen) CameraColors.OnLight else CameraColors.Label)
                Text(model.detail, style = CameraType.Caption,
                    color = if (chosen) CameraColors.OnLight.copy(alpha = 0.75f) else CameraColors.LabelSecondary)
            }
        }
        Text("Auto requests an available NNAPI accelerator, including a MediaTek NPU when its driver accepts the model. Driver selection does not prove every operator runs on the NPU. CPU fallback is reported below.",
            style = CameraType.Caption, color = CameraColors.LabelSecondary)
        Text("These models identify people, not arbitrary object classes. Synchronized main-lens depth can protect foreground objects. Without a person mask or usable depth the image stays sharp. Quality may pause live masking if too slow; it still runs on the captured photo.",
            style = CameraType.Caption, color = CameraColors.LabelSecondary)
        Text(state.bokehModelStatus, style = CameraType.Caption, color = CameraColors.Label)
        state.bokehModelError?.let { Text("Failed: $it. Select again or run checks to retry.", style = CameraType.Caption, color = CameraColors.Label) }
        Text("Runtime checks", style = CameraType.Heading, color = CameraColors.Label)
        Text("Point at a person. Compare both choices on one frame. These checks measure execution and mask range—not segmentation accuracy or sustained frame rate.",
            style = CameraType.Caption, color = CameraColors.LabelSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelCheckButton("Check selected", enabled, { actions.onRunBokehChecks(false) }, Modifier.weight(1f))
            ModelCheckButton("Compare both", enabled, { actions.onRunBokehChecks(true) }, Modifier.weight(1f))
        }
        if (state.bokehChecksRunning) ModelCheckButton("Cancel checks", true, actions::onCancelBokehChecks)
        Text(state.bokehCheckStatus, style = CameraType.Caption, color = CameraColors.LabelSecondary)
        state.bokehCheckResults.forEach { result ->
            Column(Modifier.fillMaxWidth().background(CameraColors.Matte, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text(result.model.label + if (result.passed) " · Pass" else " · Fail", style = CameraType.Value, color = CameraColors.Label)
                Text(result.summary, style = CameraType.Caption, color = CameraColors.LabelSecondary)
            }
        }
        if (state.bokehCheckResults.isNotEmpty()) ModelCheckButton("Copy report", !state.bokehChecksRunning, actions::onCopyBokehChecks)
    }
}

@Composable
private fun ModelCheckButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(label, style = CameraType.Value, color = if (enabled) CameraColors.Label else CameraColors.LabelTertiary,
        modifier = modifier.fillMaxWidth().heightIn(min = CameraSpacing.touch).clip(RoundedCornerShape(CameraSpacing.controlRadius))
            .background(CameraColors.ControlSurface).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp))
}
