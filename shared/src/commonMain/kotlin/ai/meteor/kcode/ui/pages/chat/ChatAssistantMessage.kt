@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Error

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ToolUseInfo
import ai.meteor.kcode.model.ToolUseStatus
import ai.meteor.kcode.ui.component.MarkdownText
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
@Composable
internal fun AssistantMessageTimeline(
    message: ChatMessage,
    compact: Boolean,
    onLongPressText: (String, Int) -> Unit,
) {
    if (message.isError) {
        LongPressMessageText(
            content = message.content.trimEnd(),
            color = Error,
            style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            onLongPressText = onLongPressText,
        )
        return
    }

    val timeline = message.toolUses.sortedBy(ToolUseInfo::textOffset)
    var cursor = 0
    timeline.forEach { toolUse ->
        val offset = toolUse.textOffset.coerceIn(cursor, message.content.length)
        AssistantMarkdownSegment(message.content.substring(cursor, offset), compact, onLongPressText)
        ToolUseRow(toolUse = toolUse, compact = compact)
        cursor = offset
    }
    AssistantMarkdownSegment(message.content.substring(cursor), compact, onLongPressText)
}

@Composable
private fun AssistantMarkdownSegment(
    content: String,
    compact: Boolean,
    onLongPressText: (String, Int) -> Unit,
) {
    if (content.isBlank()) return
    MarkdownText(
        markdown = content.trim(),
        compact = compact,
        color = Ink,
        onLongPressText = onLongPressText,
    )
}

@Composable
private fun ToolUseRow(toolUse: ToolUseInfo, compact: Boolean) {
    var expanded by remember(toolUse.id) { mutableStateOf(false) }
    val runningAlpha = if (toolUse.status == ToolUseStatus.Running) {
        val runningTransition = rememberInfiniteTransition(label = "tool-running-${toolUse.id}")
        val animatedAlpha by runningTransition.animateFloat(
            initialValue = .35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
            label = "tool-running-alpha",
        )
        animatedAlpha
    } else {
        1f
    }
    val statusText = when (toolUse.status) {
        ToolUseStatus.Running -> text(UiText.ToolRunning)
        ToolUseStatus.Succeeded -> text(UiText.ToolSucceeded)
        ToolUseStatus.Failed -> text(UiText.ToolFailed)
    }
    val statusColor = when (toolUse.status) {
        ToolUseStatus.Running -> LeafInk
        ToolUseStatus.Succeeded -> LeafInk
        ToolUseStatus.Failed -> Error
    }
    val summary = toolUseSummary(toolUse.input)

    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = KcodeSpacing.xs)
            .pressClickable { expanded = !expanded }
            .clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = 2.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                when (toolUse.status) {
                    ToolUseStatus.Running -> Box(
                        Modifier.size(7.dp).alpha(runningAlpha).clip(CircleShape).background(Leaf),
                    )
                    ToolUseStatus.Succeeded -> KcodeIcon(KcodeIconAsset.Check, LeafInk, Modifier.size(15.dp))
                    ToolUseStatus.Failed -> KcodeIcon(KcodeIconAsset.Info, Error, Modifier.size(15.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = KcodeSpacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        toolUseDisplayName(toolUse.name),
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "  $statusText",
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        color = SoftInk,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                    )
                }
            }
            KcodeIcon(
                KcodeIconAsset.ChevronDown,
                SoftInk,
                Modifier.size(15.dp).rotate(if (expanded) 180f else 0f),
            )
        }

        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
            Column(
                Modifier.fillMaxWidth().padding(start = KcodeSpacing.lg, top = KcodeSpacing.xs, end = KcodeSpacing.hair)
                    .clip(RoundedCornerShape(KcodeRadius.control)).background(Panel)
                    .padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs),
            ) {
                ToolUseDetail(text(UiText.ToolInput), toolUse.input)
                if (toolUse.output.isNotBlank()) ToolUseDetail(text(UiText.ToolOutput), toolUse.output)
            }
        }
    }
}

@Composable
private fun ToolUseDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.hair)) {
        Text(label.uppercase(), color = SoftInk, style = MaterialTheme.typography.labelSmall)
        Text(
            value.ifBlank { "—" },
            color = Ink,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 40,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun toolUseDisplayName(name: String): String = when (name.lowercase()) {
    "android_shell", "shell", "bash" -> "Shell"
    "read_file" -> "Read"
    "write_file" -> "Write"
    "read_media_file" -> "Read media"
    "edit_file" -> "Edit"
    "list_directory" -> "List"
    else -> name.split('_', '-').joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

private fun toolUseSummary(input: String): String {
    val compact = input.replace(Regex("\\s+"), " ").trim()
    if (compact.isEmpty() || compact == "{}") return ""
    val preferredValue = Regex(
        "\\\"(?:command|path|query|url|phoneNumber|message)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"",
    ).find(compact)?.groupValues?.getOrNull(1)
    return (preferredValue ?: compact)
        .replace("\\n", " ")
        .replace("\\\"", "\"")
        .let { if (it.length <= 180) it else it.take(179) + "…" }
}

@Composable
internal fun AssistantActions(
    modifier: Modifier = Modifier,
    canRegenerate: Boolean,
    canShare: Boolean,
    regenerateDescription: String,
    shareDescription: String,
    onRegenerate: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier.height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canRegenerate) {
            Box(
                Modifier.size(30.dp)
                    .pressClickable(style = PressScaleStyle.Button, onClick = onRegenerate)
                    .clip(CircleShape)
                    .semantics { contentDescription = regenerateDescription; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { KcodeIcon(KcodeIconAsset.Regenerate, SoftInk, Modifier.size(20.dp)) }
        }
        if (canShare) {
            Box(
                Modifier.size(30.dp)
                    .pressClickable(style = PressScaleStyle.Button, onClick = onShare)
                    .clip(CircleShape)
                    .semantics { contentDescription = shareDescription; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { KcodeIcon(KcodeIconAsset.Share, SoftInk, Modifier.size(20.dp)) }
        }
    }
}

@Composable
internal fun ThinkingRow(compact: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "thinking-alpha",
    )
    Row(
        Modifier.widthIn(max = if (compact) 680.dp else 760.dp)
            .fillMaxWidth(if (compact) 1f else .78f)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Leaf))
        Text(text(UiText.Thinking), Modifier.padding(start = KcodeSpacing.xs), color = SoftInk, style = MaterialTheme.typography.bodyMedium)
    }
}
