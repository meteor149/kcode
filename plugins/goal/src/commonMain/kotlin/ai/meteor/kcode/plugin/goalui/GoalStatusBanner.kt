package ai.meteor.kcode.plugin.goalui

import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.design.Error
import ai.meteor.kcode.ui.design.KcodeSpacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val CompletedGoalBannerVisibilityMillis = 3_000L

internal fun ThreadGoal.completedBannerRemainingMillis(nowMillis: Long): Long? {
    if (status != ThreadGoalStatus.Complete) return null
    val elapsedMillis = (nowMillis - updatedAt).coerceAtLeast(0L)
    return (CompletedGoalBannerVisibilityMillis - elapsedMillis).coerceAtLeast(0L)
}

@Composable
internal fun GoalStatusBanner(
    modifier: Modifier,
    goal: ThreadGoal,
    statusName: String,
    goalLabel: String,
    tokensLabel: String,
    pauseLabel: String,
    resumeLabel: String,
    cancelLabel: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    var expanded by remember(goal.goalId) { mutableStateOf(false) }
    Surface(
        modifier = modifier.animateContentSize()
            .pressClickable(style = PressScaleStyle.Panel) { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$goalLabel · $statusName",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (!expanded) {
                    Text(
                        text = goal.objective,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
                Text(
                    text = buildString {
                        append(tokensLabel).append(' ').append(goal.tokensUsed)
                        goal.tokenBudget?.let { append('/').append(it) }
                    },
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                )
                KcodeIcon(
                    KcodeIconAsset.ChevronDown,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
                )
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
                Column(Modifier.fillMaxWidth().padding(top = KcodeSpacing.sm)) {
                    Text(
                        text = goal.objective,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = KcodeSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.sm),
                    ) {
                        when (goal.status) {
                            ThreadGoalStatus.Active -> GoalActionButton(
                                modifier = Modifier.weight(1f),
                                label = pauseLabel,
                                onClick = {
                                    expanded = false
                                    onPause()
                                },
                            )
                            ThreadGoalStatus.Complete -> Unit
                            else -> GoalActionButton(
                                modifier = Modifier.weight(1f),
                                label = resumeLabel,
                                onClick = {
                                    expanded = false
                                    onResume()
                                },
                            )
                        }
                        GoalActionButton(
                            modifier = Modifier.weight(1f),
                            label = cancelLabel,
                            destructive = true,
                            onClick = {
                                expanded = false
                                onCancel()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalActionButton(
    modifier: Modifier,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.pressClickable(style = PressScaleStyle.Button, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (destructive) MaterialTheme.colorScheme.errorContainer else {
            MaterialTheme.colorScheme.surface.copy(alpha = .68f)
        },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
            color = if (destructive) Error else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
