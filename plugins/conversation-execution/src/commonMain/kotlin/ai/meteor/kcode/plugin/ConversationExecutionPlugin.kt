package ai.meteor.kcode.plugin

import ai.meteor.kcode.execution.HistoryConversationExecution
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import ai.meteor.kcode.plugin.api.KcodeConversationCommands
import ai.meteor.kcode.plugin.api.KcodeMessageCodec
import ai.meteor.kcode.plugin.api.KcodeExecution
import ai.meteor.kcode.plugin.api.KcodeHistory
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object ConversationExecutionProviderPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-conversation-execution"
    override val inject = dependencies(KcodeHistory.Key, KcodeConversationCommands.Key, KcodeMessageCodec.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val commands = ctx.require(KcodeConversationCommands.Key)
        val execution = HistoryConversationExecution(
            ctx.require(KcodeHistory.Key).repository,
            ctx.require(KcodeMessageCodec.Key).codec,
            admission = ctx.root[KcodeExecution.Key]?.admission,
        ) { commands.committedSnapshot }
        KcodeConversationExecution(ctx, execution)
        effect.collect(Disposable { execution.close() })
    }
}

object ConversationCommandsServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-conversation-commands"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeConversationCommands(ctx)
    }
}
