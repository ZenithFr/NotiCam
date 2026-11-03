package com.zenith.auxcam.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.CaptureMode
import com.zenith.auxcam.FlashMode
import com.zenith.auxcam.Resolution
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraType

/**
 * The grouped settings content rendered inside [SettingsPage].
 *
 * Every implemented control is a plain-language row. Search filters whole groups
 * through [ControlSection.matches]; with an empty query every group is shown.
 * Nothing here advertises unimplemented processing (HDR, night, astro, waveform).
 */
@Composable
fun CameraSettings(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
    query: String = "",
) {
    fun visible(section: ControlSection) = query.isBlank() || section.matches(query)
    val photo = state.mode == CaptureMode.Photo
    val captureEditable = state.sessionReady && !state.captureBusy && !state.videoRecording &&
        !state.videoStarting && !state.previewTransitioning

    Column(modifier) {
        if (ControlSection.entries.none { visible(it) }) {
            Text(
                "No settings match “$query”.",
                style = CameraType.RowBody, color = CameraColors.LabelTertiary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }

        // ── Capture ────────────────────────────────────────────────────────
        if (visible(ControlSection.Capture)) {
            SettingsGroup(if (photo) "Capture" else "Video") {
                if (photo) {
                    SettingsChoiceRow(
                        icon = NotiIcons.Aspect,
                        title = "Photo size",
                        subtitle = if (state.raw) "Set by the open-gate readout below"
                            else "Largest native JPEG · quality 100",
                        options = listOf("12 MP", "50 MP"),
                        selectedIndex = if (state.resolution == Resolution.High) 1 else 0,
                        onSelect = { index ->
                            val wantHigh = index == 1
                            if ((state.resolution == Resolution.High) != wantHigh) actions.onResolutionToggle()
                        },
                        enabled = captureEditable && !state.raw,
                        optionEnabled = { index -> index == 0 || state.supportsHighResolution },
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = NotiIcons.Report,
                        title = "Open gate RAW",
                        subtitle = "Full-sensor DNG readout · never merged",
                        checked = state.raw,
                        onToggle = actions::onRawToggle,
                        enabled = captureEditable,
                    )
                    if (state.raw) {
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = NotiIcons.Aspect,
                            title = "Sensor readout",
                            subtitle = if (!state.rawCapabilityChecked) "Checking RAW metadata…"
                                else "${state.rawSensorWidth} × ${state.rawSensorHeight} · " +
                                    (if (state.resolution == Resolution.High) "open gate, full field" else "binned, full field"),
                            options = listOf("Binned", "Open gate"),
                            selectedIndex = if (state.resolution == Resolution.High) 1 else 0,
                            onSelect = { actions.onRawResolutionSelected(it == 1) },
                            enabled = state.rawCapabilityChecked,
                            optionEnabled = { index ->
                                if (index == 0) state.rawStandardWidth > 0 else state.rawHighWidth > 0
                            },
                        )
                        SettingsDivider()
                        SettingsChoiceRow(
                            icon = NotiIcons.FrameRate,
                            title = "RAW frames",
                            subtitle = "Bounded burst · 128 MiB payload limit",
                            options = (1..state.maxRawFrames.coerceAtLeast(1)).map { "$it" },
                            selectedIndex = (state.rawFrames - 1).coerceAtLeast(0),
                            onSelect = { actions.onRawFramesSelected(it + 1) },
                            enabled = state.supportsRaw,
                        )
                    }
                    SettingsDivider()
                    SettingsRow(
                        icon = NotiIcons.Timer,
                        title = "Self-timer",
                        subtitle = "Delay before the shutter fires",
                        enabled = captureEditable,
                        onClick = actions::onTimerCycle,
                        trailing = { SettingsValue(if (state.timerSeconds == 0) "Off" else "${state.timerSeconds}s") },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = when (state.flash) {
                            FlashMode.Off -> NotiIcons.FlashOff
                            FlashMode.Auto -> NotiIcons.FlashAuto
                            FlashMode.On -> NotiIcons.FlashOn
                        },
                        title = "Flash",
                        subtitle = "Tap to cycle off, auto, on",
                        enabled = captureEditable && !state.raw,
                        onClick = actions::onFlashClick,
                        trailing = { SettingsValue(state.flash.name) },
                    )
                    SettingsNote(
                        "RAW size and frame count: hold the top-left RAW button for 3 seconds. " +
                            "HDR merge and Ultra HDR export are not implemented yet.",
                    )
                } else {
                    VideoProfileSettings(state, actions)
                }
            }
        }

        // ── Portrait ───────────────────────────────────────────────────────
        if (photo && visible(ControlSection.Portrait)) {
            SettingsGroup("Portrait") {
                BokehModelSettings(state, actions)
                if (state.apertureVisible) {
                    SettingsDivider()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Aperture · ${state.fStopLabel}", style = CameraType.RowTitle, color = CameraColors.Label)
                        IosSlider(
                            value = state.apertureFraction, valueRange = 0f..1f,
                            onValueChange = { actions.onApertureChanged(fStopForFraction(it)) },
                        )
                    }
                }
            }
        }

        // ── Viewfinder aids ────────────────────────────────────────────────
        if (visible(ControlSection.Aids)) {
            SettingsGroup("Viewfinder") {
                SettingsSwitchRow(
                    icon = NotiIcons.Grid,
                    title = "Grid & guides",
                    subtitle = "Composition overlay · never saved",
                    checked = state.grid,
                    onToggle = actions::onGridToggle,
                )
                SettingsDivider()
                SettingsRow(
                    icon = NotiIcons.Focus,
                    title = "Framing guide",
                    subtitle = "Tap to cycle the overlay shape",
                    enabled = state.grid,
                    onClick = actions::onFramingGuideCycle,
                    trailing = { SettingsValue(state.framingGuide.label) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = NotiIcons.Aspect,
                    title = "Preview",
                    subtitle = "${state.previewWidth} × ${state.previewHeight} · ${state.previewTargetFps} fps target",
                    trailing = { SettingsValue("${state.previewMeasuredFps.toInt()} fps", showChevron = false) },
                )
                SettingsNote(
                    "${state.previewFallback} Still resolution and original JPEG quality are independent of this preview.",
                )
            }
        }

        // ── System / diagnostics ───────────────────────────────────────────
        if (visible(ControlSection.System)) {
            SettingsGroup("System") {
                SettingsRow(
                    icon = NotiIcons.Backend,
                    title = "Shizuku camera backend",
                    subtitle = "Privileged Camera2 access · not root",
                    trailing = {
                        SettingsValue(if (state.sessionReady) "Connected" else "Waiting", showChevron = false)
                    },
                )
                SettingsDivider()
                SettingsRow(
                    icon = NotiIcons.Report,
                    title = "Camera report",
                    subtitle = "Share capabilities, permissions and streams",
                    onClick = actions::onCameraReport,
                    trailing = { SettingsValue("", showChevron = true) },
                )
                if (state.telemetry.isNotBlank()) {
                    SettingsDivider()
                    Text(
                        text = state.telemetry, style = CameraType.Telemetry,
                        color = CameraColors.LabelQuaternary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** A quiet explanatory note inside a settings card. */
@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text, style = CameraType.Caption, color = CameraColors.LabelTertiary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
    )
    Spacer(Modifier.height(0.dp))
}
