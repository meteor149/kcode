package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.model.SubAgentInfo
import ai.meteor.kcode.model.SubAgentRunStatus
import ai.meteor.kcode.ui.component.BottomSheetOverlay
import ai.meteor.kcode.ui.component.KcodeHazeState
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.MarkdownText
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.kcodeGlassEffect
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.SoftInk
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun SubAgentRunStatus.isRunning(): Boolean =
    this == SubAgentRunStatus.Pending ||
        this == SubAgentRunStatus.Running ||
        this == SubAgentRunStatus.Waiting

@Composable
internal fun RunningSubAgentOverlay(
    modifier: Modifier,
    agents: List<SubAgentInfo>,
    hazeState: KcodeHazeState,
) {
    var selectedAgentPath by remember { mutableStateOf<String?>(null) }
    val selectedAgent = agents.firstOrNull { it.path == selectedAgentPath }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        agents.chunked(2).forEach { rowAgents ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowAgents.forEach { agent ->
                    RunningSubAgentBanner(
                        modifier = Modifier.weight(1f),
                        agent = agent,
                        hazeState = hazeState,
                        onClick = { selectedAgentPath = agent.path },
                    )
                }
                if (rowAgents.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    selectedAgent?.let { agent ->
        SubAgentDetailDialog(
            agent = agent,
            onDismissRequest = { selectedAgentPath = null },
        )
    }
}

@Composable
private fun RunningSubAgentBanner(
    modifier: Modifier,
    agent: SubAgentInfo,
    hazeState: KcodeHazeState,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val transition = rememberInfiniteTransition(label = "subagent-running-${agent.path}")
    val runningAlpha by transition.animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
        label = "subagent-running-alpha",
    )
    val status = when (agent.status) {
        SubAgentRunStatus.Pending -> text(UiText.SubAgentPending)
        SubAgentRunStatus.Running -> text(UiText.ToolRunning)
        SubAgentRunStatus.Waiting -> text(UiText.SubAgentWaiting)
        else -> ""
    }
    val subAgentLabel = text(UiText.SubAgent)
    val details = agent.currentTool?.let(::toolUseDisplayName) ?: agent.path

    Column(
        modifier = modifier
            .kcodeGlassEffect(hazeState, shape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .62f), shape)
            .pressClickable(style = PressScaleStyle.Panel, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "$subAgentLabel ${agent.taskName}, $status"
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(7.dp).alpha(runningAlpha).clip(CircleShape).background(Leaf))
            }
            Text(
                text = "$subAgentLabel · $status",
                modifier = Modifier.weight(1f).padding(start = KcodeSpacing.xs),
                color = LeafInk,
                style = MaterialTheme.typography.labelMedium,
            )
            KcodeIcon(KcodeIconAsset.ChevronRight, SoftInk, Modifier.size(16.dp))
        }
        Text(
            text = agent.taskName,
            modifier = Modifier.padding(top = 2.dp),
            color = Ink,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = details,
            color = SoftInk,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SubAgentDetailDialog(
    agent: SubAgentInfo,
    onDismissRequest: () -> Unit,
) {
    val closeDescription = text(UiText.Cancel)
    BottomSheetOverlay(
        onDismissRequest = onDismissRequest,
        compactHeightFraction = .82f,
        sheetMaxWidth = 620.dp,
        sheetMaxHeight = 720.dp,
    ) { dismiss ->
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(agent.taskName, color = Ink, style = MaterialTheme.typography.titleMedium)
                    Text(
                        agent.path,
                        color = SoftInk,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Box(
                    Modifier.size(42.dp)
                        .pressClickable(style = PressScaleStyle.Button, onClick = dismiss)
                        .clip(CircleShape)
                        .semantics {
                            role = Role.Button
                            contentDescription = closeDescription
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    KcodeIcon(KcodeIconAsset.Close, Ink, Modifier.size(20.dp))
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                SubAgentDetailLabel(text(UiText.ToolInput))
                MarkdownText(markdown = agent.prompt.ifBlank { "—" }, compact = false, color = Ink)
                agent.currentTool?.let { tool ->
                    Spacer(Modifier.height(KcodeSpacing.lg))
                    SubAgentDetailLabel(text(UiText.ToolRunning))
                    Text(
                        toolUseDisplayName(tool),
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (agent.output.isNotBlank()) {
                    Spacer(Modifier.height(KcodeSpacing.lg))
                    SubAgentDetailLabel(text(UiText.ToolOutput))
                    MarkdownText(markdown = agent.output, compact = false, color = Ink)
                }
            }
        }
    }
}

@Composable
private fun SubAgentDetailLabel(label: String) {
    Text(
        label.uppercase(),
        Modifier.padding(bottom = KcodeSpacing.xs),
        color = SoftInk,
        style = MaterialTheme.typography.labelSmall,
    )
}
