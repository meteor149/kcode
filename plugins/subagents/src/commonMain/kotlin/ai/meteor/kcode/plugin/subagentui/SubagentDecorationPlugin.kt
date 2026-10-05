package ai.meteor.kcode.plugin.subagentui

import ai.meteor.kcode.model.SubAgentInfo
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.api.KcodeSubagents
import ai.meteor.kcode.plugin.ui.api.ConversationDecoration
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationContent
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPosition
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPresenter
import ai.meteor.kcode.plugin.ui.api.ConversationPageContext
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.ui.component.rememberKcodeHazeState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Presentation is optional; withdrawing UI/localization never suspends the coordinator. */
object SubagentDecorationPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "subagent-decoration"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeSubagents.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val presenter = SubagentDecorationPresenter()
        effect.collect { presenter.withdraw() }
        effect.collect(ctx.require(KcodeUiSlots.Key).registerConversationDecoration(
            ConversationDecoration("subagents", 200, presenter),
        ))
    }
}

internal fun runningSubagents(context: ConversationPageContext): List<SubAgentInfo> =
    context.conversation?.messages.orEmpty().flatMap { it.subAgents }
        .associateBy { it.path }.values.filter { it.status.isRunning() }

internal class SubagentDecorationPresenter : ConversationDecorationPresenter {
    private val active = MutableStateFlow(true)
    fun withdraw() { active.value = false }

    @Composable
    override fun Present(context: ConversationPageContext): List<ConversationDecorationContent> {
        if (!active.collectAsState().value) return emptyList()
        val agents = runningSubagents(context)
        if (agents.isEmpty()) return emptyList()
        val haze = context.hazeState ?: rememberKcodeHazeState()
        return listOf(ConversationDecorationContent(
            occupiedHeight = 0.dp,
            renderer = UiRenderer { modifier ->
                if (active.collectAsState().value) RunningSubAgentOverlay(modifier, agents, haze)
            },
            position = ConversationDecorationPosition.AboveComposer,
        ))
    }
}
