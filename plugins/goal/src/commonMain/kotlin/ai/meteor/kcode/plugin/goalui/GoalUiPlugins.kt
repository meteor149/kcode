@file:OptIn(kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.plugin.goalui

import ai.meteor.kcode.chat.ConversationResponseRequest
import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import ai.meteor.kcode.plugin.api.ExecutionAdmission
import ai.meteor.kcode.plugin.api.KcodeExecution
import ai.meteor.kcode.plugin.api.KcodeGoals
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.ConversationDecoration
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationContent
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPresenter
import ai.meteor.kcode.plugin.ui.api.ConversationPageContext
import ai.meteor.kcode.plugin.ui.api.ConversationPageEffect
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import kotlin.time.Clock

object GoalDecorationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-ui-goal-decoration"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeGoals.Key, KcodeConversationExecution.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val actions = GoalUiActions(ctx.require(KcodeGoals.Key).sessions, ctx.require(KcodeConversationExecution.Key).executor,
            ctx.root[KcodeExecution.Key]?.admission)
        effect.collect(Disposable { actions.close() })
        effect.collect(ctx.require(KcodeUiSlots.Key).registerConversationDecoration(
            ConversationDecoration("goal", 100, GoalDecorationPresenter(actions)),
        ))
    }
}

object GoalRestorationEffectPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-goal-chat-restoration"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeGoals.Key, KcodeConversationExecution.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val sessions = ctx.require(KcodeGoals.Key).sessions
        val execution = ctx.require(KcodeConversationExecution.Key).executor
        val admission = ctx.root[KcodeExecution.Key]?.admission
        val owner = PluginOperationOwner(name)
        effect.collect { owner.close() }
        effect.collect(ctx.require(KcodeUiSlots.Key).registerConversationEffect(
            ConversationPageEffect("goal.restore", 100, UiRenderer { request ->
                LaunchedEffect(request.conversation, request.configuration) {
                    owner.runIfOpen { restoreGoal(request, sessions, execution, admission) }
                }
            }),
        ))
    }
}

internal suspend fun restoreGoal(
    request: ConversationPageContext,
    sessions: GoalSessionFactory,
    execution: ConversationExecution,
    admission: ExecutionAdmission? = null,
) = admitted(admission) {
    restoreAdmittedGoal(request, sessions, execution)
}

private suspend fun <T> admitted(admission: ExecutionAdmission?, block: suspend () -> T): T =
    if (admission == null) block() else admission.run(block)

private suspend fun restoreAdmittedGoal(request: ConversationPageContext, sessions: GoalSessionFactory, execution: ConversationExecution) {
    val target = request.conversation ?: return
    val configuration = request.configuration ?: return
    val goal = target.goal ?: return
    if (!target.shouldResumeGoal || target.isGenerating || goal.status != ThreadGoalStatus.Active) return
    val session = sessions.create(target) ?: return
    val prompt = session.continuationPrompt() ?: return
    val accepted = execution.startResponse(target, ConversationResponseRequest(
        prompt = prompt,
        goalSession = session,
        scheduledTaskSession = request.scheduledTaskCoordinator.sessionFor(target.id, target.title),
    ), configuration, request.service, request.generationRunner, request.failureMessages, request.followBottom)
    if (accepted) target.shouldResumeGoal = false
}

internal class GoalUiActions(
    private val sessions: GoalSessionFactory,
    private val execution: ConversationExecution,
    private val admission: ExecutionAdmission? = null,
) {
    private data class Ownership(val live: Boolean = true, val jobs: Set<Job> = emptySet())
    private val ownership = MutableStateFlow(Ownership())
    fun dispatch(parent: CoroutineScope, request: ConversationPageContext, action: ThreadGoalStatus?) {
        if (!ownership.value.live) return
        val target = request.conversation ?: return
        if (action == ThreadGoalStatus.Active && target.isGenerating) return
        val operation = parent.launch(start = CoroutineStart.LAZY) {
            try {
                admitted(admission) {
                    val running = target.runningJob
                    if (action == ThreadGoalStatus.Active && target.isGenerating) return@admitted
                    if (action != ThreadGoalStatus.Active) {
                        target.shouldResumeGoal = false
                        running?.cancel()
                    }
                    running?.join()
                    val session = sessions.create(target) ?: return@admitted
                    when (action) {
                        null -> if (target.goal != null) session.clearGoal()
                        ThreadGoalStatus.Active -> {
                            session.setStatusFromUser(ThreadGoalStatus.Active)
                            target.shouldResumeGoal = true
                            restoreAdmittedGoal(request, sessions, execution)
                        }
                        else -> if (target.goal?.status != action) session.setStatusFromUser(action)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val detail = error.message ?: request.failureMessages.connectionFailed
                target.executionFailure = request.configuration?.apiKey?.takeIf { it.isNotEmpty() }
                    ?.let { detail.replace(it, "••••") } ?: detail
            }
        }
        var accepted = false
        ownership.update { current ->
            accepted = current.live && !operation.isCancelled
            if (accepted) current.copy(jobs = current.jobs + operation) else current
        }
        if (accepted) {
            operation.invokeOnCompletion { ownership.update { it.copy(jobs = it.jobs - operation) } }
            operation.start()
        } else {
            operation.cancel()
        }
    }
    suspend fun close() = withContext(NonCancellable) {
        val jobs = ownership.getAndUpdate { it.copy(live = false, jobs = emptySet()) }.jobs
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }
}

private class GoalDecorationPresenter(private val actions: GoalUiActions) : ConversationDecorationPresenter {
    @Composable
    override fun Present(context: ConversationPageContext): List<ConversationDecorationContent> {
        val goal = context.conversation?.goal
        val density = LocalDensity.current
        var occupiedHeight by remember(goal?.goalId) { mutableStateOf(54.dp) }
        val scope = rememberCoroutineScope()
        val request by rememberUpdatedState(context)
        var hidden by remember(context.conversation, goal) {
            mutableStateOf(goal?.completedBannerRemainingMillis(Clock.System.now().toEpochMilliseconds()) == 0L)
        }
        LaunchedEffect(context.conversation, goal) {
            val remaining = goal?.completedBannerRemainingMillis(Clock.System.now().toEpochMilliseconds()) ?: return@LaunchedEffect
            if (remaining > 0) delay(remaining)
            hidden = true
        }
        if (goal == null || (goal.status == ThreadGoalStatus.Complete && hidden)) return emptyList()
        val status = text(when (goal.status) {
            ThreadGoalStatus.Active -> UiText.GoalActive
            ThreadGoalStatus.Paused -> UiText.GoalPaused
            ThreadGoalStatus.Blocked -> UiText.GoalBlocked
            ThreadGoalStatus.UsageLimited -> UiText.GoalUsageLimited
            ThreadGoalStatus.BudgetLimited -> UiText.GoalBudgetLimited
            ThreadGoalStatus.Complete -> UiText.GoalComplete
        })
        val label = text(UiText.Goal)
        val tokens = text(UiText.GoalTokens)
        val pause = text(UiText.GoalPauseAction)
        val resume = text(UiText.GoalResumeAction)
        val cancel = text(UiText.GoalCancelAction)
        return listOf(ConversationDecorationContent(occupiedHeight, UiRenderer { modifier ->
            GoalStatusBanner(modifier.onSizeChanged {
                occupiedHeight = with(density) { it.height.toDp() } + 12.dp
            }, goal, status, label, tokens, pause, resume, cancel,
                onPause = { actions.dispatch(scope, request, ThreadGoalStatus.Paused) },
                onResume = { actions.dispatch(scope, request, ThreadGoalStatus.Active) },
                onCancel = { actions.dispatch(scope, request, null) })
        }))
    }
}
