package com.zenith.auxcam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.PortraitRuntime
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraSpacing
import com.zenith.auxcam.ui.theme.CameraType

/** Honest portrait status for the settings card. The effect is blocked until the
 * trained INT8 matting export and a verified APU-only runtime exist; no alternate
 * CPU/GPU/depth-only backend is offered. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun BokehModelSettings(state: CameraUiState, actions: CameraActions) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(NotiIcons.Portrait, contentDescription = null, tint = CameraColors.LabelSecondary,
                modifier = Modifier.size(CameraSpacing.rowIcon))
            Spacer(Modifier.width(14.dp))
            Text("Portrait effect", style = CameraType.RowTitle, color = CameraColors.Label,
                modifier = Modifier.weight(1f))
            Text(if (state.bokehActive) "On" else "Unavailable", style = CameraType.Value,
                color = if (state.bokehActive) CameraColors.Label else CameraColors.LabelTertiary)
        }
        Text("MobileNetV4-Conv-Medium · LRASPP/matting · INT8", style = CameraType.RowBody,
            color = CameraColors.LabelSecondary)
        Text(
            "Requested pipeline: 512 × 512 input → soft alpha → guided edge refinement → full-resolution bokeh. The saved frame is not downsampled.",
            style = CameraType.Caption, color = CameraColors.LabelTertiary,
        )
        Text(PortraitRuntime.blockedReason, style = CameraType.Caption, color = CameraColors.Label)
        Text(
            "The old MediaPipe/MODNet engines and model downloads were removed. A classification backbone is not a trained matting model, and no alternate inference backend is selected.",
            style = CameraType.Caption, color = CameraColors.LabelTertiary,
        )
    }
}
