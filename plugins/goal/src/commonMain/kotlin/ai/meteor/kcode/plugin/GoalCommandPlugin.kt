package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ConversationCommand
import ai.meteor.kcode.chat.ConversationCommandContribution
import ai.meteor.kcode.chat.ConversationCommandRequest
import ai.meteor.kcode.plugin.goal.GoalCommand
import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.plugin.goal.goalContinuationPrompt
import ai.meteor.kcode.plugin.goal.parseGoalCommand
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.api.KcodeConversationCommands
import ai.meteor.kcode.plugin.api.KcodeGoals
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object GoalCommandConsumerPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-command-goal"
    override val inject = dependencies(KcodeConversationCommands.Key, KcodeGoals.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val catalog = ctx.require(KcodeLocalization.Key).catalog
        val handler = GoalCommandHandler(ctx.require(KcodeGoals.Key).sessions) { language, value -> catalog.translate(language, value) }
        effect.collect(ctx.require(KcodeConversationCommands.Key).register(handler.contribution()))
    }
}

internal class GoalCommandHandler(
    private val sessions: GoalSessionFactory,
    private val localize: suspend (AppLanguage, LocalizedText) -> String,
) {
    private val locks = mutableMapOf<ConversationState, Mutex>()
    fun contribution() = ConversationCommandContribution("goal", order = 100) { input ->
        parseGoalCommand(input)?.let { parsed ->
            object : ConversationCommand {
                override val allowedDuringGeneration = true
                override suspend fun execute(request: ConversationCommandRequest) {
                    val seed = when (parsed) {
                        is GoalCommand.Set -> parsed.objective
                        is GoalCommand.Edit -> parsed.objective
                        else -> request.prompt
                    }
                    val target = request.conversation ?: request.onSendToNew(seed)
                    locks.getOrPut(target) { Mutex() }.withLock { execute(parsed, request, target) }
                }
            }
        }
    }

    private suspend fun execute(command: GoalCommand, request: ConversationCommandRequest, target: ConversationState) {
        val session = sessions.create(target) ?: return
        val user = ChatMessage(request.operations.nextMessageId(target), MessageRole.User, request.prompt)
        suspend fun text(value: LocalizedText) = localize(request.language, value)
        suspend fun feedback(content: String, error: Boolean = false) =
            request.operations.appendFeedback(target, user, content, error)
        suspend fun noGoal() = feedback(text(UiText.GoalNoGoal), true)
        suspend fun stop(cancel: Boolean) {
            val running = target.runningJob
            if (cancel) running?.cancel()
            running?.join()
        }
        when (command) {
            GoalCommand.Show -> feedback(session.getGoal()?.let { summarize(request.language, it) }
                ?: text(UiText.GoalNoGoal))
            GoalCommand.Pause, GoalCommand.Clear -> {
                if (session.getGoal() == null) return noGoal()
                stop(cancel = true)
                if (command == GoalCommand.Clear) {
                    session.clearGoal()
                    feedback(text(UiText.GoalCleared))
                } else {
                    feedback(summarize(request.language, session.setStatusFromUser(ThreadGoalStatus.Paused)))
                }
            }
            GoalCommand.Resume, is GoalCommand.Set, is GoalCommand.Edit -> {
                val objective = when (command) {
                    is GoalCommand.Set -> command.objective
                    is GoalCommand.Edit -> command.objective
                    else -> null
                }
                if (objective != null && objective.isBlank()) return feedback(text(UiText.GoalObjectiveRequired), true)
                if (command !is GoalCommand.Set && session.getGoal() == null) return noGoal()
                stop(cancel = command != GoalCommand.Resume)
                val goal = try {
                    when (command) {
                        is GoalCommand.Set -> session.setGoalFromUser(command.objective)
                        is GoalCommand.Edit -> session.editGoalFromUser(command.objective)
                        else -> session.setStatusFromUser(ThreadGoalStatus.Active)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    feedback(error.message.orEmpty(), true)
                    return
                }
                request.operations.startResponse(target, user, goalContinuationPrompt(goal), session)
            }
        }
    }

    private suspend fun summarize(language: AppLanguage, goal: ThreadGoal): String {
        val status = when (goal.status) {
            ThreadGoalStatus.Active -> UiText.GoalActive
            ThreadGoalStatus.Paused -> UiText.GoalPaused
            ThreadGoalStatus.Blocked -> UiText.GoalBlocked
            ThreadGoalStatus.UsageLimited -> UiText.GoalUsageLimited
            ThreadGoalStatus.BudgetLimited -> UiText.GoalBudgetLimited
            ThreadGoalStatus.Complete -> UiText.GoalComplete
        }
        return buildString {
            append(localize(language, UiText.Goal)).append(": ").append(goal.objective)
            append('\n').append(localize(language, UiText.GoalStatus)).append(": ").append(localize(language, status))
            append('\n').append(localize(language, UiText.GoalTokens)).append(": ").append(goal.tokensUsed)
            goal.tokenBudget?.let { append(" / ").append(it) }
        }
    }
}
