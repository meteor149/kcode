@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.pages.chat.component

import ai.meteor.kcode.ui.design.Mist
import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.state.ConversationState

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.MessageContentRequest
import androidx.compose.runtime.key
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.PressScaleStyle
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.meteor.kcode.ui.component.KcodeHazeState
import ai.meteor.kcode.ui.component.kcodeHazeSource
import kotlinx.coroutines.delay
import ai.meteor.kcode.ui.component.KcodeBrandMark
@Composable
fun ConversationMessageList(
    modifier: Modifier,
    compact: Boolean,
    conversation: ConversationState,
    messages: List<ChatMessage> = conversation.messages,
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
    anchoredUserMessageId: Long?,
    messageAnchorTop: Dp,
    actionsEnabled: Boolean = true,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val anchoredUserIndex = messages.indexOfFirst { it.id == anchoredUserMessageId }
        val anchoredAssistantId = anchoredUserIndex.takeIf { it >= 0 }
            ?.let { messages.getOrNull(it + 1)?.id }
        var userHeightPx by remember(anchoredUserMessageId) { mutableStateOf(0) }
        var assistantHeightPx by remember(anchoredUserMessageId) { mutableStateOf(0) }
        var thinkingHeightPx by remember(anchoredUserMessageId) { mutableStateOf(0) }
        val showTurnTailSpace = anchoredUserIndex >= 0
        val reserveThinkingSpace = conversation.isAwaitingFirstToken || thinkingHeightPx > 0
        val measuredTurnHeight = with(density) {
            (userHeightPx + assistantHeightPx + thinkingHeightPx).toDp()
        }
        val turnItemCount = 2 + if (reserveThinkingSpace) 1 else 0
        val tailSpace = (
            maxHeight - messageAnchorTop - contentPadding.calculateBottomPadding() -
                measuredTurnHeight - itemSpacing * turnItemCount
        ).coerceAtLeast(0.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { onBackgroundTap() }
            },
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            items(messages, key = { it.id }) { message ->
                val measurementModifier = when (message.id) {
                    anchoredUserMessageId -> Modifier.onSizeChanged { userHeightPx = it.height }
                    anchoredAssistantId -> Modifier.onSizeChanged { assistantHeightPx = it.height }
                    else -> Modifier
                }
                Box(measurementModifier) {
                    MessageItem(
                        message = message,
                        compact = compact,
                        canRegenerate = actionsEnabled && !selectionMode && configurationAvailable && !conversation.isGenerating,
                        canShare = actionsEnabled && !selectionMode && !conversation.isGenerating,
                        selectionMode = selectionMode,
                        selected = message.id in selectedMessageIds,
                        regenerateDescription = regenerateDescription,
                        shareDescription = shareDescription,
                        onToggleSelection = { onToggleSelection(message) },
                        onShare = { onShare(message) },
                        onRegenerate = { onRegenerate(message) },
                    )
                }
            }
            if (conversation.isGenerating && conversation.isAwaitingFirstToken) {
                item(key = "thinking") {
                    Box(Modifier.onSizeChanged { thinkingHeightPx = it.height }) {
                        ThinkingRow(compact = compact)
                    }
                }
            }
            if (showTurnTailSpace) {
                item(key = "turn-tail-space") {
                    Spacer(Modifier.height(tailSpace))
                }
            }
        }
    }
}

@Composable
fun MessageItem(
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
    val markdown = LocalApplicationUiSlots.current.markdown
    val selectableText = remember(message.id, message.content, markdown) { markdown?.plainText(message.content).orEmpty() }
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
                    RenderRegisteredMessage(MessageContentRequest(message, compact, activateTextSelection))
                } else {
                    Column(
                        Modifier.widthIn(max = if (compact) 680.dp else 760.dp)
                            .fillMaxWidth(if (compact) 1f else .78f),
                    ) {
                        if (message.content.isNotEmpty() || message.toolUses.isNotEmpty()) {
                            RenderRegisteredMessage(MessageContentRequest(message, compact, activateTextSelection))
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
fun KcodeMark(size: Dp) {
    KcodeBrandMark(Modifier.size(size))
}

@Composable
private fun RenderRegisteredMessage(request: MessageContentRequest) {
    val presentation = LocalApplicationUiSlots.current.messagePresentations.firstOrNull { it.supports(request.message) }
    if (presentation != null) {
        key(presentation) { presentation.renderer.Render(request) }
    }
}

/** Reusable visual primitive; choosing this renderer belongs to a plugin. */
@Composable
fun UserMessageContent(request: MessageContentRequest) {
    val compact = request.compact
    val message = request.message
    val activateTextSelection = request.onLongPressText
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
}
