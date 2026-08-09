@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.ui.design.Mist
import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.state.ConversationState

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.markdownToPlainText
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kcode.shared.generated.resources.Res
import kcode.shared.generated.resources.kcode_mark
import org.jetbrains.compose.resources.painterResource
@Composable
internal fun ConversationMessageList(
    modifier: Modifier,
    compact: Boolean,
    conversation: ConversationState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    itemSpacing: Dp,
    configurationAvailable: Boolean,
    selectionMode: Boolean,
    selectedMessageIds: Set<Long>,
    regenerateDescription: String,
    shareDescription: String,
    onBackgroundTap: () -> Unit,
    onToggleSelection: (ChatMessage) -> Unit,
    onShare: (ChatMessage) -> Unit,
    onRegenerate: (ChatMessage) -> Unit,
) {
    LazyColumn(
        state = listState,
        reverseLayout = compact,
        modifier = modifier.pointerInput(Unit) { detectTapGestures { onBackgroundTap() } },
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        if (compact && conversation.isGenerating && conversation.isAwaitingFirstToken) {
            item(key = "thinking") { ThinkingRow(compact = true) }
        }
        val messages = if (compact) conversation.messages.asReversed() else conversation.messages
        items(messages, key = { it.id }) { message ->
            MessageItem(
                message = message,
                compact = compact,
                canRegenerate = !selectionMode && configurationAvailable && !conversation.isGenerating,
                canShare = !selectionMode && !conversation.isGenerating,
                selectionMode = selectionMode,
                selected = message.id in selectedMessageIds,
                regenerateDescription = regenerateDescription,
                shareDescription = shareDescription,
                onToggleSelection = { onToggleSelection(message) },
                onShare = { onShare(message) },
                onRegenerate = { onRegenerate(message) },
            )
        }
        if (!compact && conversation.isGenerating && conversation.isAwaitingFirstToken) {
            item(key = "thinking") { ThinkingRow() }
        }
    }
}

@Composable
internal fun Welcome(
    modifier: Modifier,
    compact: Boolean,
    configuration: ModelConfiguration?,
    setupMessage: String?,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onSend: (String) -> Unit,
    onModelClick: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
    toolPermissionControlsAvailable: Boolean,
    toolPermissionMode: ToolPermissionMode,
    onToolPermissionModeChange: (ToolPermissionMode) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    if (compact) {
        Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    detectTapGestures { focusManager.clearFocus(force = true) }
                },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    KcodeMark(size = 52.dp)
                    Text(
                        text(UiText.WelcomeTitle),
                        Modifier.padding(top = KcodeSpacing.lg),
                        color = Ink,
                        style = MaterialTheme.typography.displaySmall,
                    )
                    if (configuration == null || setupMessage != null) {
                        Text(
                            setupMessage ?: text(UiText.WelcomeBody),
                            Modifier.padding(top = KcodeSpacing.sm, start = KcodeSpacing.lg, end = KcodeSpacing.lg),
                            color = SoftInk,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            MobileComposer(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                configuration = configuration,
                generating = false,
                replying = false,
                focusRequester = focusRequester,
                onFocus = onFocus,
                onModelClick = onModelClick,
                onConfigurationChange = onConfigurationChange,
                onSend = onSend,
                onStop = {},
                toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                toolPermissionMode = toolPermissionMode,
                onToolPermissionModeChange = onToolPermissionModeChange,
            )
        }
        return
    }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KcodeMark(size = 58.dp)
        Text(
            text(UiText.WelcomeTitle),
            Modifier.padding(top = KcodeSpacing.lg),
            color = Ink,
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            setupMessage ?: text(UiText.WelcomeBody),
            Modifier.padding(top = KcodeSpacing.xs, bottom = KcodeSpacing.xl),
            color = SoftInk,
            style = MaterialTheme.typography.bodyMedium,
        )
        Composer(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(.78f),
            generating = false,
            focusRequester = focusRequester,
            onFocus = onFocus,
            onSend = onSend,
            onStop = {},
            toolPermissionControlsAvailable = toolPermissionControlsAvailable,
            toolPermissionMode = toolPermissionMode,
            onToolPermissionModeChange = onToolPermissionModeChange,
        )
        Row(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(.78f).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val sendSuggestion: (String) -> Unit = {
                focusManager.clearFocus(force = true)
                onSend(it)
            }
            Suggestion(text(UiText.SuggestionIdea), Modifier.weight(1f), sendSuggestion)
            Suggestion(text(UiText.SuggestionCode), Modifier.weight(1f), sendSuggestion)
            Suggestion(text(UiText.SuggestionPlan), Modifier.weight(1f), sendSuggestion)
        }
    }
}

@Composable
internal fun MessageItem(
    message: ChatMessage,
    compact: Boolean = false,
    canRegenerate: Boolean,
    canShare: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    regenerateDescription: String,
    shareDescription: String,
    onToggleSelection: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val selectionShape = RoundedCornerShape(KcodeRadius.card)
    val clipboard = LocalClipboardManager.current
    val selectableText = remember(message.id, message.content) { markdownToPlainText(message.content) }
    var textSelection by remember(message.id) { mutableStateOf<TextFieldValue?>(null) }
    var intendedSentenceSelection by remember(message.id) { mutableStateOf<TextRange?>(null) }
    val selectionFocusRequester = remember(message.id) { FocusRequester() }
    val activateTextSelection: (String, Int) -> Unit = { blockText, blockOffset ->
        if (!selectionMode && selectableText.isNotEmpty()) {
            val blockRange = sentenceSelectionRange(blockText, blockOffset)
            val sentence = blockText.substring(blockRange.start, blockRange.end)
            val sentenceStart = selectableText.indexOf(sentence).takeIf { it >= 0 }
                ?: blockText.takeIf { it == selectableText }?.let { blockRange.start }
                ?: 0
            val range = if (sentence.isNotBlank() && sentenceStart >= 0) {
                TextRange(sentenceStart, (sentenceStart + sentence.length).coerceAtMost(selectableText.length))
            } else {
                sentenceSelectionRange(selectableText, sentenceStart)
            }
            intendedSentenceSelection = range
            textSelection = TextFieldValue(selectableText, range)
        }
    }
    LaunchedEffect(textSelection != null) {
        if (textSelection != null) {
            // Android may briefly replace a programmatic range with its word-level long-press
            // selection while focus settles. Reapply the sentence once, then yield control to
            // the user's draggable handles.
            delay(120)
            intendedSentenceSelection?.let { range ->
                textSelection = TextFieldValue(selectableText, range)
                intendedSentenceSelection = null
            }
        }
    }
    Box(
        Modifier.fillMaxWidth()
            .background(
                color = if (selected) Leaf.copy(alpha = .1f) else Color.Transparent,
                shape = selectionShape,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                    SelectionIndicator(selected = selected)
                }
            }
            Box(
                Modifier.weight(1f),
                contentAlignment = if (message.role == MessageRole.User) Alignment.CenterEnd else Alignment.Center,
            ) {
                if (textSelection != null) {
                    MessageSelectionEditor(
                        value = textSelection!!,
                        onValueChange = { textSelection = it.copy(text = selectableText) },
                        focusRequester = selectionFocusRequester,
                        compact = compact,
                        isUser = message.role == MessageRole.User,
                        isError = message.isError,
                    )
                } else if (message.role == MessageRole.User) {
                    Box(
                        Modifier.widthIn(max = if (compact) 560.dp else 620.dp)
                            .fillMaxWidth(if (compact) .86f else .68f)
                            .clip(RoundedCornerShape(KcodeRadius.card))
                            .background(Panel)
                            .padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
                    ) {
                        LongPressMessageText(
                            content = message.content.trimEnd(),
                            color = Ink,
                            style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            onLongPressText = activateTextSelection,
                        )
                    }
                } else {
                    Column(
                        Modifier.widthIn(max = if (compact) 680.dp else 760.dp)
                            .fillMaxWidth(if (compact) 1f else .78f),
                    ) {
                        if (message.content.isNotEmpty() || message.toolUses.isNotEmpty()) {
                            AssistantMessageTimeline(
                                message = message,
                                compact = compact,
                                onLongPressText = activateTextSelection,
                            )
                            if (canRegenerate || canShare) {
                                AssistantActions(
                                    canRegenerate = canRegenerate,
                                    canShare = canShare,
                                    regenerateDescription = regenerateDescription,
                                    shareDescription = shareDescription,
                                    onRegenerate = onRegenerate,
                                    onShare = onShare,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (selectionMode) {
            Box(
                Modifier.matchParentSize()
                    .pressClickable(style = PressScaleStyle.Panel, onClick = onToggleSelection)
                    .semantics { role = Role.Checkbox },
            )
        }
        KcodeBubblePopup(
            expanded = textSelection != null,
            onDismissRequest = { textSelection = null },
            placement = BubblePlacement.Above,
            minWidth = 104.dp,
            maxWidth = 116.dp,
            maxHeight = 68.dp,
            focusable = false,
        ) {
            CompactCopyAction(
                label = text(UiText.CopyText),
                onClick = {
                    val value = textSelection ?: return@CompactCopyAction
                    val start = minOf(value.selection.start, value.selection.end)
                    val end = maxOf(value.selection.start, value.selection.end)
                    if (start < end) clipboard.setText(AnnotatedString(value.text.substring(start, end)))
                    textSelection = null
                },
            )
        }
    }
}

@Composable
private fun Suggestion(label: String, modifier: Modifier, onClick: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier.clip(RoundedCornerShape(KcodeRadius.control))
            .background(if (hovered) Hairline.copy(alpha = .7f) else Mist)
            .hoverable(interaction)
            .pressScale(interaction, PressScaleStyle.Panel)
            .clickable(interactionSource = interaction, indication = null) { onClick(label) }
            .padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = SoftInk, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun KcodeMark(size: Dp) {
    Image(
        painter = painterResource(Res.drawable.kcode_mark),
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}
