package com.zenith.auxcam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.zenith.auxcam.ui.theme.CameraType

@Composable
fun BokehModelSettings(state: CameraUiState, actions: CameraActions) {
    val enabled = !state.captureBusy && !state.bokehChecksRunning && !state.videoStarting && !state.videoRecording && !state.previewTransitioning
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bokeh model", style = CameraType.Heading, color = CameraColors.Label)
        Text("Saved for the portrait-effect button. Ordinary JPEG and 50 MP capture stay unchanged. All models are bundled; no image uploads.",
            style = CameraType.Caption, color = CameraColors.LabelSecondary)
        BokehModel.entries.forEach { model ->
            val chosen = state.bokehModel == model
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (chosen) CameraColors.ControlSurface else CameraColors.Matte)
                .semantics { selected = chosen }
                .clickable(enabled = enabled, role = Role.RadioButton) { actions.onBokehModelSelected(model) }
                .padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text((if (chosen) "✓  " else "") + model.label, style = CameraType.Value,
                    color = if (enabled || chosen) CameraColors.Label else CameraColors.LabelTertiary)
                Text(model.detail, style = CameraType.Caption, color = CameraColors.LabelSecondary)
            }
        }
        Text("Live preview is separate from photo capture. Repeatedly slow models pause live effects, but still run on the received portrait photo. A failed/timed-out portrait saves sharp with an explicit message; it never disables the shutter.",
            style = CameraType.Caption, color = CameraColors.LabelSecondary)
        Text(state.bokehModelStatus, style = CameraType.Caption, color = CameraColors.LabelSecondary)
        state.bokehModelError?.let { error ->
            Text("Failed: $error. No model substituted. Select again or run checks.", style = CameraType.Caption, color = CameraColors.Label)
        }
        Spacer(Modifier.height(6.dp))
        Text("Model checks", style = CameraType.Heading, color = CameraColors.Label)
        Text("Point at a person in Photo. Check selected or compare all on one fresh frame: hash, tensor contract, warmup, 5 timed runs and alpha validity. Common mask output: 256 × 256.",
            style = CameraType.Caption, color = CameraColors.LabelSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelCheckButton("CHECK SELECTED", enabled, { actions.onRunBokehChecks(false) }, Modifier.weight(1f))
            ModelCheckButton("CHECK ALL", enabled, { actions.onRunBokehChecks(true) }, Modifier.weight(1f))
        }
        if (state.bokehChecksRunning) ModelCheckButton("CANCEL CHECKS", true, actions::onCancelBokehChecks)
        Text(state.bokehCheckStatus, style = CameraType.Caption, color = CameraColors.Label)
        state.bokehCheckResults.forEach { result ->
            Column(Modifier.fillMaxWidth().background(CameraColors.Matte, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text(result.model.label + if (result.passed) " · PASS" else " · FAIL", style = CameraType.Value, color = CameraColors.Label)
                Text(result.summary, style = CameraType.Caption, color = CameraColors.LabelSecondary)
            }
        }
        if (state.bokehCheckResults.isNotEmpty()) ModelCheckButton("COPY REPORT", !state.bokehChecksRunning, actions::onCopyBokehChecks)
        Text("PASS means the runtime worked—not better hair detail or sustained 60 fps. RVM timing reuses one frame; judge motion separately in the live portrait preview. Switching resets temporal history. RVM is GPL-3.0-only; see bundled model licenses before distribution.",
            style = CameraType.Caption, color = CameraColors.LabelSecondary)
    }
}

@Composable
private fun ModelCheckButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(label, style = CameraType.Caption, color = if (enabled) CameraColors.Label else CameraColors.LabelTertiary,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CameraColors.ControlSurface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp))
}
