package com.zenith.auxcam.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.zenith.auxcam.ui.theme.CameraSpacing
import androidx.compose.ui.text.style.TextOverflow
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.SensorControlPolicy
import com.zenith.auxcam.ui.effects.cameraGlass
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraType

private enum class RailControl { Exposure, Iso, Shutter, Temperature, Focus }

/** Original implementation of an instrument rail: real controls, not preset looks.
 * Fixed height across selections; interpolated presentation never feeds capture requests.
 */
@Composable
fun CaptureControlRail(state: CameraUiState, actions: CameraActions) {
    var selected by remember { mutableStateOf(RailControl.Exposure) }
    val ready = state.canAdjustSensorControls
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 5.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            InstrumentChip(if (state.manualExposure) "AE" else "AUTO", if (state.manualExposure) "Manual" else state.evLabel, selected == RailControl.Exposure,
                ready && (!state.manualExposure || state.manualExposureAvailable)) {
                selected = RailControl.Exposure
                if (state.manualExposure) actions.onManualExposureToggle()
            }
            InstrumentChip("ISO", if (state.manualExposure) state.sensorIso.toString() else "Auto", selected == RailControl.Iso,
                ready && state.manualExposureAvailable) {
                selected = RailControl.Iso
                if (!state.manualExposure) actions.onManualExposureToggle()
            }
            InstrumentChip("SHUTTER", if (state.manualExposure) SensorControlPolicy.shutterLabel(state.sensorExposureNs) else "Auto",
                selected == RailControl.Shutter, ready && state.manualExposureAvailable) {
                selected = RailControl.Shutter
                if (!state.manualExposure) actions.onManualExposureToggle()
            }
            InstrumentChip("WB", if (state.whiteBalanceKelvin == 0) "Auto" else "≈${state.whiteBalanceKelvin}K",
                selected == RailControl.Temperature, ready && state.sensorControls.supportsTemperature) {
                selected = RailControl.Temperature
            }
            InstrumentChip("FOCUS", state.focusLabel, selected == RailControl.Focus, ready) {
                selected = RailControl.Focus
            }
        }
        Crossfade(targetState = selected, animationSpec = CameraMotion.fade(140), label = "capture-control-rail") { control ->
            Box(Modifier.fillMaxWidth().height(CameraSpacing.railSlider), contentAlignment = Alignment.Center) {
                if (!ready) {
                    Text("Waiting for current lens controls…", style = CameraType.Caption, color = CameraColors.LabelSecondary)
                } else when (control) {
                    RailControl.Exposure -> if (!state.manualExposure) IosSlider(state.evFraction, 0f..1f,
                        { if (selected == control) actions.onEvChanged(it * 8f - 4f) }, Modifier.height(CameraSpacing.railSlider))
                    else Text("Switching to Auto exposure…", style = CameraType.Caption, color = CameraColors.LabelSecondary)
                    RailControl.Iso -> if (state.manualExposure && state.manualExposureAvailable) IosSlider(
                        SensorControlPolicy.fraction(state.sensorIso.toLong(), state.sensorControls.minIso.toLong(), state.sensorControls.maxIso.toLong()),
                        0f..1f, { if (selected == control) actions.onSensorIsoChanged(it) }, Modifier.height(CameraSpacing.railSlider),
                        detents = SensorControlPolicy.isoStops(state.sensorControls.minIso, state.sensorControls.maxIso)
                            .map { SensorControlPolicy.fraction(it, state.sensorControls.minIso.toLong(), state.sensorControls.maxIso.toLong()) })
                    else Text("Tap ISO to enable manual exposure", style = CameraType.Caption, color = CameraColors.LabelSecondary)
                    RailControl.Shutter -> if (state.manualExposure && state.manualExposureAvailable) IosSlider(
                        SensorControlPolicy.fraction(state.sensorExposureNs, state.sensorControls.minExposureNs, state.maxManualExposureNs),
                        0f..1f, { if (selected == control) actions.onSensorShutterChanged(it) }, Modifier.height(CameraSpacing.railSlider),
                        detents = SensorControlPolicy.shutterStops(state.sensorControls.minExposureNs, state.maxManualExposureNs)
                            .map { SensorControlPolicy.fraction(it, state.sensorControls.minExposureNs, state.maxManualExposureNs) })
                    else Text("Tap SHUTTER to enable manual exposure", style = CameraType.Caption, color = CameraColors.LabelSecondary)
                    RailControl.Temperature -> Row(verticalAlignment = Alignment.CenterVertically) {
                        IosSlider((state.whiteBalanceKelvin.takeIf { it > 0 } ?: 6500).toFloat(), 2000f..10000f,
                            { if (selected == control) actions.onWhiteBalanceTemperatureChanged(it.toInt()) },
                            Modifier.weight(1f).height(CameraSpacing.railSlider))
                        ResetChip(onClick = { actions.onWhiteBalanceTemperatureChanged(0) })
                    }
                    RailControl.Focus -> Row(verticalAlignment = Alignment.CenterVertically) {
                        IosSlider(state.focusFraction, 0f..1f, { if (selected == control) actions.onManualFocusChanged(it) }, Modifier.weight(1f).height(CameraSpacing.railSlider))
                        ResetChip(actions::onFocusReset)
                    }
                }
            }
        }
    }
}

@Composable
private fun InstrumentChip(label: String, value: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val opacity by animateFloatAsState(if (enabled) 1f else 0.4f, CameraMotion.fade(), label = "instrument-availability")
    val foreground = CameraColors.Label
    Column(Modifier.widthIn(min = 64.dp).height(CameraSpacing.touch * LocalDensity.current.fontScale.coerceAtLeast(1f))
        .graphicsLayer { alpha = opacity }
        .semantics { selected = active }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 4.dp)
        .cameraGlass(RoundedCornerShape(CameraSpacing.controlRadius), if (active) CameraColors.Accent else CameraColors.Matte)
        .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, style = CameraType.Telemetry, color = foreground.copy(alpha = 0.65f), maxLines = 1)
        Text(value, style = CameraType.Value, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
