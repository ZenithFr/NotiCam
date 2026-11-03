package com.zenith.auxcam.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenith.auxcam.CameraActions
import com.zenith.auxcam.CameraUiState
import com.zenith.auxcam.CaptureMode
import com.zenith.auxcam.ui.feedback.rememberCameraHaptics
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraMotion
import com.zenith.auxcam.ui.theme.CameraSpacing
import com.zenith.auxcam.ui.theme.CameraType

/**
 * Full-screen settings page.
 *
 * Replaces the old cramped bottom sheet. Every implemented control lives here,
 * grouped into labelled cards with one row per setting: a leading glyph, a plain
 * sentence-case title, a one-line description, and the control itself. Search
 * filters the groups; system back and the header button both return to camera.
 *
 * The page is an overlay — it never resizes the native preview underneath, and
 * it reads the same immutable [CameraUiState] / [CameraActions] as the chrome.
 */
@Composable
fun SettingsPage(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val bodyScroll = rememberScrollState()
    LaunchedEffect(query) { bodyScroll.scrollTo(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CameraColors.PageBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = CameraSpacing.pageGutter),
    ) {
        // ── Header ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MatteIconButton(
                icon = NotiIcons.Back,
                contentDescription = "Back to camera",
                onClick = actions::onSettingsToggle,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Settings", style = CameraType.PageTitle, color = CameraColors.Label)
                Text(
                    text = if (state.mode == CaptureMode.Video) "Video · native frame" else "Photo · original JPEG",
                    style = CameraType.Caption, color = CameraColors.LabelTertiary,
                )
            }
        }

        // ── Search ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CameraColors.CardSurface)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NotiIcons.Search, contentDescription = null, tint = CameraColors.LabelTertiary,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query, onValueChange = { query = it.take(80) }, singleLine = true,
                textStyle = CameraType.RowTitle.copy(color = CameraColors.Label),
                cursorBrush = SolidColor(CameraColors.Label),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Search settings" },
                decorationBox = { input ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Search settings", style = CameraType.RowTitle,
                            color = CameraColors.LabelQuaternary)
                        input()
                    }
                },
            )
            if (query.isNotEmpty()) {
                MatteIconButton(icon = NotiIcons.Close, contentDescription = "Clear search",
                    onClick = { query = "" })
            }
        }

        // ── Grouped settings body ──────────────────────────────────────────
        Column(
            modifier = Modifier.weight(1f).verticalScroll(bodyScroll).padding(bottom = 32.dp),
        ) {
            CameraSettings(state = state, actions = actions, query = query)
            Spacer(Modifier.height(16.dp))
            Text(
                "NotiCam keeps the original frame. Settings change capture and the viewfinder, never the saved pixels.",
                style = CameraType.Caption, color = CameraColors.LabelQuaternary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** A labelled group of settings rows inside one flat card. */
@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = CameraSpacing.sectionGap)) {
        Text(
            text = title, style = CameraType.SectionLabel, color = CameraColors.LabelTertiary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(CameraSpacing.cardRadius))
                .background(CameraColors.CardSurface)
                .padding(vertical = CameraSpacing.cardPadding),
        ) { content() }
    }
}

/**
 * One settings row: glyph, title + description, and a trailing control. When
 * [onClick] is supplied the whole row is the touch target (>= 60 dp tall).
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = rememberCameraHaptics()
    val tint by animateColorAsState(
        targetValue = if (pressed && onClick != null) CameraColors.CardRaised else Color.Transparent,
        animationSpec = tween(120), label = "row-press",
    )
    Row(
        modifier = modifier.fillMaxWidth()
            .heightIn(min = CameraSpacing.rowMinHeight)
            .background(tint)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction, indication = null, enabled = enabled,
                    role = Role.Button, onClick = { haptics.tap(); onClick() },
                ) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon, contentDescription = null,
            tint = if (enabled) CameraColors.LabelSecondary else CameraColors.LabelQuaternary,
            modifier = Modifier.size(CameraSpacing.rowIcon),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title, style = CameraType.RowTitle,
                color = if (enabled) CameraColors.Label else CameraColors.LabelQuaternary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle, style = CameraType.RowBody,
                    color = if (enabled) CameraColors.LabelTertiary else CameraColors.LabelQuaternary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A row whose whole surface toggles a matte switch. Big target, clear state. */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberCameraHaptics()
    SettingsRow(
        icon = icon, title = title, subtitle = subtitle, enabled = enabled, modifier = modifier
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "On" else "Off"
            }
            .clickable(enabled = enabled, role = Role.Switch, onClick = { haptics.select(); onToggle() }),
        trailing = { NotiSwitch(checked = checked, enabled = enabled) },
    )
}

/** A row with a full-width segmented choice beneath the title. */
@Composable
fun SettingsChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    optionEnabled: (Int) -> Boolean = { true },
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon, contentDescription = null,
                tint = if (enabled) CameraColors.LabelSecondary else CameraColors.LabelQuaternary,
                modifier = Modifier.size(CameraSpacing.rowIcon),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = CameraType.RowTitle,
                    color = if (enabled) CameraColors.Label else CameraColors.LabelQuaternary)
                if (subtitle != null) Text(subtitle, style = CameraType.RowBody,
                    color = CameraColors.LabelTertiary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        NotiSegmented(
            options = options, selectedIndex = selectedIndex, onSelect = onSelect,
            enabled = enabled, optionEnabled = optionEnabled,
        )
    }
}

/** Matte switch: white track + black thumb when on, quiet grey when off. */
@Composable
fun NotiSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val track by animateColorAsState(
        targetValue = if (checked) Color.White else CameraColors.SwitchTrackOff,
        animationSpec = tween(180), label = "switch-track",
    )
    val thumb by animateColorAsState(
        targetValue = if (checked) CameraColors.OnLight else CameraColors.LabelTertiary,
        animationSpec = tween(180), label = "switch-thumb",
    )
    val knobX by animateDpAsState(
        targetValue = if (checked) 21.dp else 3.dp,
        animationSpec = CameraMotion.select(), label = "switch-knob",
    )
    Box(
        modifier = modifier.width(46.dp).height(28.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(RoundedCornerShape(14.dp))
            .background(track),
    ) {
        Box(
            modifier = Modifier.padding(start = knobX).align(Alignment.CenterStart)
                .size(22.dp).clip(CircleShape).background(thumb),
        )
    }
}

/** Segmented control: one white selection, quiet raised track, no layout shift. */
@Composable
fun NotiSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionEnabled: (Int) -> Boolean = { true },
) {
    val haptics = rememberCameraHaptics()
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CameraColors.CardRaised)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val usable = enabled && optionEnabled(index)
            val bg by animateColorAsState(
                targetValue = if (selected) Color.White else Color.Transparent,
                animationSpec = tween(180), label = "segment-bg",
            )
            val fg by animateColorAsState(
                targetValue = when {
                    selected -> CameraColors.OnLight
                    usable -> CameraColors.LabelSecondary
                    else -> CameraColors.LabelQuaternary
                },
                animationSpec = tween(180), label = "segment-fg",
            )
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(bg)
                    .clickable(enabled = usable, role = Role.Button, onClick = { haptics.select(); onSelect(index) })
                    .padding(horizontal = 6.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label, style = CameraType.Value, color = fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A thin hairline between rows inside a card. */
@Composable
fun SettingsDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 50.dp)
            .height(1.dp).background(CameraColors.Separator),
    )
}

/** Trailing value + chevron for rows that cycle or open a deeper choice. */
@Composable
fun SettingsValue(value: String, showChevron: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(value, style = CameraType.Value, color = CameraColors.LabelSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showChevron) {
            Spacer(Modifier.width(2.dp))
            Icon(NotiIcons.ChevronRight, contentDescription = null,
                tint = CameraColors.LabelQuaternary, modifier = Modifier.size(16.dp))
        }
    }
}
