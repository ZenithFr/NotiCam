package com.zenith.auxcam.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.CaptureMode
import com.zenith.auxcam.FlashMode
import com.zenith.auxcam.Resolution
import com.zenith.auxcam.ui.effects.cameraGlass
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraType

/** Independently built, searchable controls: capture, manual, portrait, aids and diagnostics.
 * Resource evidence supports this grouping; it is not a pixel-exact rendering of MereFrame.
 */
@Composable
fun CameraSettings(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    var section by remember { mutableStateOf(ControlSection.Capture) }
    var query by remember { mutableStateOf("") }
    val bodyScroll = rememberScrollState()
    LaunchedEffect(section, query) { bodyScroll.scrollTo(0) }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Camera controls", style = CameraType.Heading, color = CameraColors.Label)
                Text(if (state.mode == CaptureMode.Video) "VIDEO · NATIVE FRAME" else if (state.raw) "RAW · ${state.rawFrames} FRAME${if (state.rawFrames == 1) "" else "S"}"
                    else "PHOTO · JPEG 100", style = CameraType.Caption, color = CameraColors.LabelSecondary)
            }
            MatteIconButton(NotiIcons.Close, "Close camera controls", actions::onSettingsToggle)
        }
        BasicTextField(value = query, onValueChange = { query = it.take(80) }, singleLine = true,
            textStyle = CameraType.Value.copy(color = CameraColors.Label),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(CameraColors.Label),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .cameraGlass(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 14.dp)
                .semantics { contentDescription = "Search camera controls" },
            decorationBox = { input -> Box { if (query.isEmpty()) Text("Search controls", style = CameraType.Value,
                color = CameraColors.LabelTertiary); input() } })
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (item in ControlSection.entries) TogglePill(item.label, section == item && query.isBlank(),
                { section = item; query = "" })
        }
        Spacer(Modifier.height(16.dp))
        // Only this body scrolls; search and section navigation remain reachable.
        Column(Modifier.weight(1f, fill = false).verticalScroll(bodyScroll).padding(bottom = 24.dp)) {
            fun visible(item: ControlSection) = if (query.isBlank()) section == item else item.matches(query)
            if (ControlSection.entries.none { visible(it) }) Text("No matching implemented controls.",
                style = CameraType.Caption, color = CameraColors.LabelSecondary)
            if (visible(ControlSection.Capture)) {
                Text("Output", style = CameraType.Heading, color = CameraColors.Label)
                Text(if (state.raw) "${state.photoWidth} × ${state.photoHeight} · RAW_SENSOR → DNG"
                    else if (state.mode == CaptureMode.Video) "Native-aspect AVC · ${state.videoTargetFps} fps target"
                    else if (state.bokehActive) "${state.photoSizeLabel} · optional portrait composite · JPEG 100"
                    else "${state.photoSizeLabel} · original sensor JPEG · quality 100",
                    style = CameraType.Caption, color = CameraColors.LabelSecondary)
                Spacer(Modifier.height(12.dp))
                if (state.mode == CaptureMode.Photo) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TogglePill("RAW (DNG)", state.raw, actions::onRawToggle, Modifier.weight(1f))
                        TogglePill(if (state.timerSeconds == 0) "TIMER OFF" else "${state.timerSeconds}s TIMER", state.timerSeconds > 0,
                            actions::onTimerCycle, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TogglePill("FLASH · ${state.flash.name.uppercase()}", state.flash != FlashMode.Off,
                            actions::onFlashClick, Modifier.weight(1f))
                        if (!state.raw) TogglePill("SIZE · ${state.photoSizeLabel}", state.resolution == Resolution.High,
                            actions::onResolutionToggle, Modifier.weight(1f))
                    }
                    Text(if (!state.rawCapabilityChecked) "Checking RAW metadata…" else if (state.supportsRaw)
                        "RAW advertised · ${state.rawSensorWidth} × ${state.rawSensorHeight}. Session support is checked when opened."
                        else "No eligible RAW_SENSOR stream within the capture payload budget. JPEG remains available.",
                        style = CameraType.Caption, color = CameraColors.LabelSecondary)
                    if (state.raw) {
                        Spacer(Modifier.height(16.dp))
                        Text("RAW sequence", style = CameraType.Heading, color = CameraColors.Label)
                        Text("Separately saved DNG frames. No alignment, merging or HDR processing is applied. Single frame remains the default.",
                            style = CameraType.Caption, color = CameraColors.LabelSecondary)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (count in 1..state.maxRawFrames) TogglePill(if (count == 1) "1 frame" else "$count frames",
                                state.rawFrames == count, { actions.onRawFramesSelected(count) }, Modifier.weight(1f))
                        }
                        Text("Up to 4 frames, 128 MiB acquired RAW payload budget. Flash and portrait are off. Each DNG retains its own matched capture metadata.",
                            style = CameraType.Caption, color = CameraColors.LabelTertiary)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            if (visible(ControlSection.Portrait)) {
                BokehModelSettings(state, actions)
                // ── Optional portrait depth ────────────────────────────────
                AnimatedVisibility(
                    visible = state.apertureVisible,
                    enter = fadeIn(tween(200)) + slideInVertically(
                        animationSpec = CameraMotion.slide<IntOffset>(),
                        initialOffsetY = { -it / 3 },
                    ),
                    exit = fadeOut(tween(120)) + slideOutVertically(
                        animationSpec = tween<IntOffset>(140),
                        targetOffsetY = { -it / 4 },
                    ),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = NotiIcons.Aperture,
                                contentDescription = "Aperture",
                                tint = CameraColors.LabelSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text("DEPTH EFFECT", style = CameraType.Section, color = CameraColors.LabelTertiary)
                            Spacer(Modifier.weight(1f))
                            Text(state.fStopLabel, style = CameraType.Value, color = CameraColors.Label)
                        }
                        IosSlider(
                            value = state.apertureFraction,
                            valueRange = 0f..1f,
                            onValueChange = { actions.onApertureChanged(fStopForFraction(it)) },
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("1.4", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                            Spacer(Modifier.weight(1f))
                            Text("2.8", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                            Spacer(Modifier.weight(1f))
                            Text("5.6", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                            Spacer(Modifier.weight(1f))
                            Text("11", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                            Spacer(Modifier.weight(1f))
                            Text("16", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }

            }
            if (visible(ControlSection.Manual)) {
                SensorControlSettings(state, actions)
                Spacer(Modifier.height(20.dp))

                // ── Exposure ───────────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EXPOSURE", style = CameraType.Section, color = CameraColors.LabelTertiary)
                    Spacer(Modifier.weight(1f))
                    Text(if (state.manualExposure) "MANUAL" else state.evLabel, style = CameraType.Value, color = CameraColors.Label)
                }
                if (!state.manualExposure) {
                    IosSlider(
                        value = state.evFraction,
                        valueRange = 0f..1f,
                        onValueChange = { actions.onEvChanged(it * 8f - 4f) },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("−4", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                        Spacer(Modifier.weight(1f))
                        Text("0", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                        Spacer(Modifier.weight(1f))
                        Text("+4", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                    }
                } else {
                    Text("EV compensation is inactive while sensor exposure is manual.", style = CameraType.Caption, color = CameraColors.LabelSecondary)
                }

                Spacer(Modifier.height(16.dp))

                // ── Focus: Manual slider with Reset chip ───────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = NotiIcons.Focus,
                        contentDescription = "Focus",
                        tint = if (state.manualFocus) CameraColors.Accent else CameraColors.LabelSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("FOCUS", style = CameraType.Section, color = CameraColors.LabelTertiary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        state.focusLabel,
                        style = CameraType.Value,
                        color = if (state.manualFocus) CameraColors.Accent else CameraColors.Label,
                    )
                    if (state.manualFocus || state.aeAfLocked) {
                        Spacer(Modifier.width(8.dp))
                        ResetChip(onClick = actions::onFocusReset)
                    }
                }
                IosSlider(
                    value = state.focusFraction,
                    valueRange = 0f..1f,
                    onValueChange = actions::onManualFocusChanged,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("∞", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("FAR", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("NEAR", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                    Spacer(Modifier.weight(1f))
                    Text("MACRO", style = CameraType.Caption, color = CameraColors.LabelQuaternary)
                }

            }
            if (visible(ControlSection.Aids)) {
                TogglePill("GRID", state.grid, actions::onGridToggle, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                TogglePill(label = "FRAMING GUIDE · ${state.framingGuide.label}", active = state.grid,
                    onClick = actions::onFramingGuideCycle, modifier = Modifier.fillMaxWidth())
                Text("Tap to cycle guides. GRID switches them off. Guides never appear in saved files.",
                    style = CameraType.Caption, color = CameraColors.LabelSecondary)

                Spacer(Modifier.height(22.dp))
                Text("Viewfinder", style = CameraType.Heading, color = CameraColors.Label)
                Text("${state.previewWidth} × ${state.previewHeight} · ${state.previewTargetFps} fps target · ${state.previewMeasuredFps.toInt()} rendered",
                    style = CameraType.Caption, color = CameraColors.LabelSecondary)
                Text(state.previewFallback, style = CameraType.Caption, color = CameraColors.LabelSecondary)
                Text("Still resolution and original JPEG quality are independent of this preview.",
                    style = CameraType.Caption, color = CameraColors.LabelSecondary)
            }
            if (visible(ControlSection.System)) {
                Text("Shizuku camera backend", style = CameraType.Heading, color = CameraColors.Label)
                Spacer(Modifier.height(6.dp))
                Text("Report service UID, permissions and exposed camera streams. Shell access does not guarantee OEM-only modes.",
                    style = CameraType.Caption, color = CameraColors.LabelSecondary)
                Spacer(Modifier.height(12.dp))
                TogglePill(label = "CAMERA REPORT", active = false, onClick = actions::onCameraReport,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.telemetry,
                    style = CameraType.Telemetry,
                    color = CameraColors.LabelTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
