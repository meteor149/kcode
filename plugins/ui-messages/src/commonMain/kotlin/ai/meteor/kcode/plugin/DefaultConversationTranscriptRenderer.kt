package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.pages.chat.component.MessageItem
import ai.meteor.kcode.plugin.pages.chat.component.scrollToConversationBottom
import ai.meteor.kcode.plugin.ui.api.ConversationTranscriptRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.ui.design.KcodeSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

object DefaultConversationTranscriptRenderer : UiRenderer<ConversationTranscriptRequest> {
    @Composable
    override fun Render(request: ConversationTranscriptRequest) {
        val listState = rememberLazyListState()
        val latest = request.messageIds.lastOrNull()?.let(request.messageForId)
        val latestVersion = latest?.let { message ->
            Triple(
                message.content.length,
                message.toolUses.joinToString { "${it.id}:${it.status}:${it.output.length}" },
                message.subAgents.joinToString { "${it.path}:${it.status}:${it.output.length}" },
            )
        }
        LaunchedEffect(request.messageIds.size, latestVersion) {
            listState.scrollToConversationBottom(request.messageIds.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = KcodeSpacing.sm,
                end = KcodeSpacing.sm,
                top = KcodeSpacing.xs,
                bottom = KcodeSpacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(KcodeSpacing.hair),
        ) {
            items(request.messageIds, key = { it }) { messageId ->
                request.messageForId(messageId)?.let { message ->
                    MessageItem(
                        message = message,
                        compact = true,
                        canRegenerate = false,
                        canShare = false,
                        selectionMode = false,
                        selected = false,
                        regenerateDescription = "",
                        shareDescription = "",
                        onToggleSelection = {},
                        onShare = {},
                        onRegenerate = {},
                    )
                }
            }
        }
    }
}
