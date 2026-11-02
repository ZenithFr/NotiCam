package com.zenith.auxcam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.PortraitRuntime
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraType

@Suppress("UNUSED_PARAMETER")
@Composable
fun BokehModelSettings(state: CameraUiState, actions: CameraActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Portrait · APU only", style = CameraType.Heading, color = CameraColors.Label)
        Text("MobileNetV4-Conv-Medium · LRASPP/matting · INT8", style = CameraType.Value, color = CameraColors.Label)
        Text("Requested pipeline: 512 × 512 input → soft alpha → guided edge refinement → full-resolution depth-weighted bokeh. The original full-resolution frame is not downsampled for saving.", style = CameraType.Caption, color = CameraColors.LabelSecondary)
        Text(PortraitRuntime.blockedReason, style = CameraType.Caption, color = CameraColors.Label)
        Text("The old MediaPipe/MODNet engines and model downloads have been removed. A classification backbone alone is not a trained matting model. No speed or hair-detail claims are verified, and no alternate inference backend is selected.", style = CameraType.Caption, color = CameraColors.LabelSecondary)
    }
}
