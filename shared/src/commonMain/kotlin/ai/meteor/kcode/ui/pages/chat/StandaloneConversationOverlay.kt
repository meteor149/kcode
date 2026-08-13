@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.state.ConversationState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.backhandler.BackHandler

@Composable
internal fun StandaloneConversationOverlay(
    conversation: ConversationState,
    pendingCount: Int,
    onAddToRecent: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val hasExplicitResult = !conversation.standaloneResult.isNullOrBlank()
    var processExpanded by remember(conversation.id) { mutableStateOf(false) }
    val result = conversation.standaloneResult.orEmpty()
    val persistedResultMessage = conversation.messages.lastOrNull()?.takeIf { message ->
        hasExplicitResult && message.role == MessageRole.Assistant && message.content == result
    }
    val resultMessage = when {
        persistedResultMessage != null -> persistedResultMessage
        hasExplicitResult -> ChatMessage(Long.MIN_VALUE, MessageRole.Assistant, result)
        else -> null
    }
    val processMessages = resultMessage?.let { selected ->
        conversation.messages.filterNot { it.id == selected.id }
    } ?: conversation.messages
    val resultListState = rememberLazyListState()
    val processListState = rememberLazyListState()
    LaunchedEffect(conversation.id, conversation.messages.size, conversation.isAwaitingFirstToken) {
        if (processMessages.isNotEmpty()) processListState.scrollToItem(processMessages.lastIndex)
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.18f))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        contentAlignment = Alignment.Center,
    ) {
        val panelPadding = if (maxWidth < 600.dp) KcodeSpacing.sm else KcodeSpacing.xxl
        val compact = maxWidth < 600.dp
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(panelPadding)
                .widthIn(max = 840.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = KcodeSpacing.xs,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = KcodeSpacing.lg,
                            end = KcodeSpacing.xs,
                            top = KcodeSpacing.sm,
                            bottom = KcodeSpacing.sm,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = text(UiText.StandaloneTask),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = conversation.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (pendingCount > 1) {
                            Text(
                                text = text(UiText.StandaloneTaskPending, pendingCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = onAddToRecent,
                        shape = RoundedCornerShape(KcodeRadius.control),
                        contentPadding = PaddingValues(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
                    ) {
                        KcodeIcon(
                            asset = KcodeIconAsset.Chat,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = text(UiText.AddToRecent),
                            modifier = Modifier.padding(start = KcodeSpacing.xs),
                            maxLines = 1,
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(KcodeSize.touchTarget),
                    ) {
                        KcodeIcon(
                            asset = KcodeIconAsset.Close,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                            contentDescription = text(UiText.CloseStandaloneConversation),
                        )
                    }
                }
                if (resultMessage != null) {
                    ConversationMessageList(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (processExpanded) Modifier.heightIn(max = 220.dp) else Modifier.weight(1f)),
                        compact = compact,
                        conversation = conversation,
                        messages = listOf(resultMessage),
                        listState = resultListState,
                        contentPadding = PaddingValues(
                            start = KcodeSpacing.lg,
                            end = KcodeSpacing.lg,
                            top = KcodeSpacing.sm,
                            bottom = KcodeSpacing.sm,
                        ),
                        itemSpacing = KcodeSpacing.md,
                        configurationAvailable = false,
                        selectionMode = false,
                        selectedMessageIds = emptySet(),
                        regenerateDescription = "",
                        shareDescription = "",
                        onBackgroundTap = {},
                        onToggleSelection = {},
                        onShare = {},
                        onRegenerate = {},
                        anchoredUserMessageId = null,
                        messageAnchorTop = 0.dp,
                        actionsEnabled = false,
                    )
                    TextButton(
                        onClick = { processExpanded = !processExpanded },
                        modifier = Modifier.padding(horizontal = KcodeSpacing.md),
                    ) {
                        KcodeIcon(
                            asset = KcodeIconAsset.ChevronDown,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).rotate(if (processExpanded) 180f else 0f),
                        )
                        Text(
                            text = text(if (processExpanded) UiText.HideTaskProcess else UiText.ShowTaskProcess),
                            modifier = Modifier.padding(start = KcodeSpacing.xs),
                        )
                    }
                }
                if (resultMessage == null || processExpanded) {
                    ConversationMessageList(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        compact = compact,
                        conversation = conversation,
                        messages = processMessages,
                        listState = processListState,
                        contentPadding = PaddingValues(
                            start = KcodeSpacing.lg,
                            end = KcodeSpacing.lg,
                            top = KcodeSpacing.sm,
                            bottom = KcodeSpacing.xl,
                        ),
                        itemSpacing = KcodeSpacing.md,
                        configurationAvailable = false,
                        selectionMode = false,
                        selectedMessageIds = emptySet(),
                        regenerateDescription = "",
                        shareDescription = "",
                        onBackgroundTap = {},
                        onToggleSelection = {},
                        onShare = {},
                        onRegenerate = {},
                        anchoredUserMessageId = null,
                        messageAnchorTop = 0.dp,
                        actionsEnabled = false,
                    )
                }
            }
        }
    }
}
