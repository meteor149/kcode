package ai.meteor.kcode

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.KcodeTheme
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.pages.chat.MessageItem
import ai.meteor.kcode.ui.pages.chat.scrollToConversationBottom
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AndroidConversationOverlayContent(
    messageIds: List<Long>,
    messageForId: (Long) -> ChatMessage?,
    title: String,
    closeDescription: String,
    onDrag: (horizontalChange: Float, verticalChange: Float) -> Unit,
    onClose: () -> Unit,
) {
    KcodeTheme {
        val shape = RoundedCornerShape(KcodeRadius.card)
        val listState = rememberLazyListState()
        val latest = messageIds.lastOrNull()?.let(messageForId)
        val latestVersion = latest?.let { message ->
            Triple(
                message.content.length,
                message.toolUses.joinToString { "${it.id}:${it.status}:${it.output.length}" },
                message.subAgents.joinToString { "${it.path}:${it.status}:${it.output.length}" },
            )
        }
        LaunchedEffect(messageIds.size, latestVersion) {
            listState.scrollToConversationBottom(messageIds.lastIndex)
        }

        Box(Modifier.fillMaxSize().padding(6.dp)) {
            Column(
                Modifier.fillMaxSize()
                    .shadow(12.dp, shape, clip = false)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .68f))
                    .border(1.dp, Hairline.copy(alpha = .72f), shape),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        }
                        .padding(start = KcodeSpacing.sm, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Leaf))
                    }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f).padding(start = 5.dp),
                        color = Ink,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier.size(32.dp)
                            .clip(CircleShape)
                            .pressClickable(style = PressScaleStyle.Button, onClick = onClose)
                            .semantics {
                                role = Role.Button
                                contentDescription = closeDescription
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        KcodeIcon(
                            asset = KcodeIconAsset.Close,
                            tint = SoftInk,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HorizontalDivider(color = Hairline.copy(alpha = .68f))
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
                    items(messageIds, key = { it }) { messageId ->
                        messageForId(messageId)?.let { message ->
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
    }
}
