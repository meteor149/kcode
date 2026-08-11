@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.chat.parseGoalCommand

import ai.meteor.kcode.ui.design.Mist
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Error

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.modelOption
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.ui.component.kcodeGlassEffect
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.modelName
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.meteor.kcode.ui.component.KcodeHazeState
@Composable
private fun MobileModelSelector(
    configuration: ModelConfiguration?,
    actionSize: Dp,
    extraCompact: Boolean,
    onMissingConfiguration: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.width(if (extraCompact) KcodeSize.narrowModelControl else KcodeSize.compactModelControl)
                .height(actionSize)
                .pressClickable(style = PressScaleStyle.Button) {
                    if (configuration == null) onMissingConfiguration() else expanded = true
                }
                .clip(RoundedCornerShape(KcodeRadius.control))
                .padding(horizontal = KcodeSpacing.hair),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                configuration?.let {
                    modelOption(it.provider, it.modelId)?.let { option -> modelName(option) }
                }
                    ?: text(UiText.ChooseModel),
                color = Ink,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Medium",
                modifier = Modifier.padding(top = KcodeSpacing.hair / 2),
                color = SoftInk,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        if (configuration != null) {
            ModelConfigurationBubble(
                expanded = expanded,
                configuration = configuration,
                placement = BubblePlacement.Above,
                onDismissRequest = { expanded = false },
                onChange = onConfigurationChange,
            )
        }
    }
}

@Composable
internal fun MobileComposer(
    modifier: Modifier,
    hazeState: KcodeHazeState,
    configuration: ModelConfiguration?,
    generating: Boolean,
    replying: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onModelClick: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    toolPermissionControlsAvailable: Boolean,
    toolPermissionMode: ToolPermissionMode,
    onToolPermissionModeChange: (ToolPermissionMode) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val sendInteraction = remember { MutableInteractionSource() }
    val goalCommandReady = generating && parseGoalCommand(value) != null
    val sendDescription = text(if (generating && !goalCommandReady) UiText.StopGeneration else UiText.SendMessage)

    fun submit() {
        if (value.isNotBlank() && (!generating || parseGoalCommand(value) != null)) {
            onSend(value)
            value = ""
        }
    }

    Surface(
        modifier = modifier.navigationBarsPadding().imePadding(),
        shape = RoundedCornerShape(KcodeRadius.panel),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Hairline.copy(alpha = .55f)),
        shadowElevation = 16.dp,
    ) {
        BoxWithConstraints {
        val extraCompact = maxWidth < 340.dp
        val actionSize = KcodeSize.compactControl
        Box(
            Modifier.matchParentSize()
                .kcodeGlassEffect(hazeState, RoundedCornerShape(KcodeRadius.panel)),
        )
        Column(
            Modifier.padding(
                start = if (extraCompact) KcodeSpacing.sm else KcodeSpacing.md,
                top = KcodeSpacing.sm,
                end = KcodeSpacing.sm,
                bottom = KcodeSpacing.xs,
            ),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 120.dp)
                    .onFocusChanged { if (it.isFocused) onFocus() }
                    .focusRequester(focusRequester),
                enabled = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Leaf),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text(if (replying) UiText.ReplyPlaceholder else UiText.MessagePlaceholder),
                                color = SoftInk.copy(alpha = .72f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(actionSize).clip(CircleShape).background(Mist),
                    contentAlignment = Alignment.Center,
                ) {
                    KcodeIcon(KcodeIconAsset.Add, Ink, Modifier.size(20.dp))
                }
                if (toolPermissionControlsAvailable) {
                    Spacer(Modifier.width(KcodeSpacing.hair))
                    ToolPermissionModeButton(
                        mode = toolPermissionMode,
                        size = actionSize,
                        onModeChange = onToolPermissionModeChange,
                    )
                }
                Spacer(Modifier.weight(1f))
                MobileModelSelector(
                    configuration = configuration,
                    actionSize = actionSize,
                    extraCompact = extraCompact,
                    onMissingConfiguration = onModelClick,
                    onConfigurationChange = onConfigurationChange,
                )
                Spacer(Modifier.width(KcodeSpacing.hair))
                Button(
                    onClick = if (generating && !goalCommandReady) onStop else ::submit,
                    enabled = generating || value.isNotBlank(),
                    modifier = Modifier.pressScale(
                        sendInteraction,
                        PressScaleStyle.Button,
                        generating || value.isNotBlank(),
                    ).size(actionSize).semantics {
                        contentDescription = sendDescription
                    },
                    interactionSource = sendInteraction,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Leaf,
                        disabledContainerColor = Mist,
                        disabledContentColor = SoftInk,
                    ),
                ) {
                    if (generating && !goalCommandReady) {
                        KcodeIcon(KcodeIconAsset.Stop, Ink.copy(alpha = .94f), Modifier.size(if (extraCompact) 12.5.dp else 14.dp))
                    } else {
                        KcodeIcon(KcodeIconAsset.Send, Ink, Modifier.size(18.dp))
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun Composer(
    modifier: Modifier,
    hazeState: KcodeHazeState,
    generating: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    toolPermissionControlsAvailable: Boolean,
    toolPermissionMode: ToolPermissionMode,
    onToolPermissionModeChange: (ToolPermissionMode) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val sendInteraction = remember { MutableInteractionSource() }
    val goalCommandReady = generating && parseGoalCommand(value) != null
    val sendDescription = text(if (generating && !goalCommandReady) UiText.StopGeneration else UiText.SendMessage)

    fun submit() {
        if (value.isNotBlank() && (!generating || parseGoalCommand(value) != null)) {
            onSend(value)
            value = ""
        }
    }

    Surface(
        modifier = modifier.navigationBarsPadding().imePadding(),
        shape = RoundedCornerShape(KcodeRadius.card),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 7.dp,
    ) {
        Box {
            Box(
                Modifier.matchParentSize()
                    .kcodeGlassEffect(hazeState, RoundedCornerShape(KcodeRadius.card)),
            )
            Column(
                Modifier.fillMaxWidth().padding(
                    start = KcodeSpacing.md,
                    top = KcodeSpacing.sm,
                    end = KcodeSpacing.sm,
                    bottom = KcodeSpacing.xs,
                ),
            ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().height(62.dp)
                    .onFocusChanged { if (it.isFocused) onFocus() }
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                            submit(); true
                        } else false
                    },
                enabled = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                cursorBrush = SolidColor(Leaf),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(
                            text(UiText.MessagePlaceholder),
                            color = SoftInk.copy(alpha = .78f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(KcodeSize.compactControl).clip(CircleShape).background(Mist),
                    contentAlignment = Alignment.Center,
                ) {
                    KcodeIcon(KcodeIconAsset.Add, Ink, Modifier.size(20.dp))
                }
                if (toolPermissionControlsAvailable) {
                    Spacer(Modifier.width(KcodeSpacing.hair))
                    ToolPermissionModeButton(
                        mode = toolPermissionMode,
                        size = KcodeSize.compactControl,
                        onModeChange = onToolPermissionModeChange,
                    )
                }
                Text(
                    text(UiText.SendShortcut),
                    Modifier.padding(start = KcodeSpacing.xs),
                    color = SoftInk.copy(alpha = .7f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = if (generating && !goalCommandReady) onStop else ::submit,
                    enabled = generating || value.isNotBlank(),
                    modifier = Modifier.pressScale(
                        sendInteraction,
                        PressScaleStyle.Button,
                        generating || value.isNotBlank(),
                    ).size(KcodeSize.compactControl).semantics {
                        contentDescription = sendDescription
                    },
                    interactionSource = sendInteraction,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Leaf,
                        disabledContainerColor = Mist,
                        disabledContentColor = SoftInk,
                    ),
                ) {
                    if (generating && !goalCommandReady) {
                        KcodeIcon(KcodeIconAsset.Stop, Ink.copy(alpha = .94f), Modifier.size(12.5.dp))
                    } else {
                        KcodeIcon(KcodeIconAsset.Send, Ink, Modifier.size(18.dp))
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ToolPermissionModeButton(
    mode: ToolPermissionMode,
    size: Dp,
    onModeChange: (ToolPermissionMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modeName = mode.code
    val description = "${text(UiText.ToolPermissionMode)}: $modeName"
    Box {
        Column(
            modifier = Modifier.width(KcodeSize.compactPermissionControl).height(size)
                .pressClickable(style = PressScaleStyle.Button) { expanded = true }
                .clip(RoundedCornerShape(KcodeRadius.control))
                .semantics { contentDescription = description; role = Role.Button }
                .padding(horizontal = KcodeSpacing.hair),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text(UiText.ToolPermissionShort),
                color = Ink,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                modeName,
                modifier = Modifier.padding(top = KcodeSpacing.hair / 2),
                color = SoftInk,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        KcodeBubblePopup(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            placement = BubblePlacement.Above,
            minWidth = 232.dp,
            maxWidth = 272.dp,
            maxHeight = 440.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = KcodeSpacing.sm)) {
                PopupNavigationRow(label = text(UiText.ToolPermissionMode), value = modeName)
                HorizontalDivider(Modifier.padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair), color = Hairline, thickness = .7.dp)
                PopupSectionLabel(text(UiText.PermissionLevel))
                ToolPermissionMode.entries.forEach { item ->
                    val itemDescription = when (item) {
                        ToolPermissionMode.Deny -> text(UiText.ToolPermissionDenyDescription)
                        ToolPermissionMode.Ask -> text(UiText.ToolPermissionAskDescription)
                        ToolPermissionMode.Bypass -> text(UiText.ToolPermissionBypassDescription)
                    }
                    PopupChoiceRow(
                        title = item.code,
                        subtitle = itemDescription,
                        selected = item == mode,
                        titleColor = if (item == ToolPermissionMode.Bypass) Error else Ink,
                        onClick = {
                            expanded = false
                            onModeChange(item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, shortcut: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(KcodeRadius.control))
            .background(if (hovered) Mist else Color.Transparent)
            .border(1.dp, Hairline, RoundedCornerShape(KcodeRadius.control))
            .hoverable(interaction)
            .pressScale(interaction, PressScaleStyle.Panel)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcodeIcon(KcodeIconAsset.Add, LeafInk, Modifier.size(19.dp))
        Text(label, Modifier.padding(start = KcodeSpacing.xs).weight(1f), color = Ink, style = MaterialTheme.typography.labelLarge)
        Text(shortcut, color = SoftInk.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
    }
}
