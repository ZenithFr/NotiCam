package com.zenith.auxcam.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zenith.auxcam.*
import com.zenith.auxcam.ui.effects.cameraGlass
import com.zenith.auxcam.ui.feedback.rememberCameraHaptics
import com.zenith.auxcam.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

/** Tap toggles RAW. A completed 3-second hold opens the sideways format/burst chooser.
 * Early release toggles once; cancelled/out-of-bounds gestures never toggle or reveal.
 */
@Composable
fun RawCaptureButton(state: CameraUiState, actions: CameraActions) {
    var expanded by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val currentActions by rememberUpdatedState(actions)
    val enabled = state.mode == CaptureMode.Photo && state.sessionReady && !state.captureBusy && !state.previewTransitioning
    val haptics = rememberCameraHaptics()
    LaunchedEffect(enabled, state.mode) { if (!enabled) { expanded = false; progress.snapTo(0f) } }
    Box {
        Box(Modifier.size(CameraSpacing.touch)
            .semantics {
                role = Role.Button
                contentDescription = "RAW ${if (state.raw) "on" else "off"}. Hold three seconds for size and frame count"
                selected = state.raw
                if (!enabled) disabled()
                onClick { if (enabled) currentActions.onRawToggle(); enabled }
                onLongClick("Choose RAW resolution and frames") { if (enabled) expanded = true; enabled }
            }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = {
                    coroutineScope {
                        var revealed = false
                        val hold = launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(3000, easing = LinearEasing))
                            revealed = true
                            expanded = true
                            haptics.select()
                        }
                        try {
                            val released = tryAwaitRelease()
                            hold.cancelAndJoin()
                            if (released && !revealed) { haptics.tap(); currentActions.onRawToggle() }
                        } finally { hold.cancel(); progress.snapTo(0f) }
                    }
                })
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp).cameraGlass(CircleShape, if (state.raw) CameraColors.Accent else CameraColors.ControlSurface),
                contentAlignment = Alignment.Center) {
                Text("RAW", style = CameraType.Telemetry,
                    color = (if (state.raw) CameraColors.OnLight else CameraColors.Label)
                        .copy(alpha = if (enabled) .9f else .4f))
            }
            if (progress.value > 0f) Canvas(Modifier.size(42.dp)) {
                drawArc(CameraColors.Label, -90f, 360f * progress.value, false, style = Stroke(2.dp.toPx()))
            }
        }
        if (expanded && enabled) Popup(alignment = Alignment.TopStart,
            offset = IntOffset(0, with(LocalDensity.current) { 48.dp.roundToPx() }),
            onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.widthIn(max = 320.dp).cameraGlass(RoundedCornerShape(16.dp)).padding(10.dp)) {
                Text("RAW · native sensor output", style = CameraType.Caption, color = CameraColors.Label)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TogglePill("12 MP", state.raw && state.resolution == Resolution.Standard,
                        { currentActions.onRawResolutionSelected(false); expanded = false },
                        enabled = state.rawCapabilityChecked && state.rawStandardWidth > 0)
                    TogglePill("50 MP", state.raw && state.resolution == Resolution.High,
                        { currentActions.onRawResolutionSelected(true); expanded = false },
                        enabled = state.rawCapabilityChecked && state.rawHighWidth > 0)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (count in 1..state.maxRawFrames.coerceAtLeast(1)) TogglePill("$count frame${if (count == 1) "" else "s"}",
                        state.rawFrames == count, { currentActions.onRawFramesSelected(count); expanded = false },
                        enabled = state.raw && state.supportsRaw)
                }
                Text("Separate DNGs · not HDR/merged. Missing sizes are disabled. 128 MiB RAW payload limit.",
                    style = CameraType.Caption, color = CameraColors.LabelSecondary)
            }
        }
    }
}
