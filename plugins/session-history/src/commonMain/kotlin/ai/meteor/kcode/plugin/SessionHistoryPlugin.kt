package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ConversationSession
import ai.meteor.kcode.chat.ConversationSessionFactory
import ai.meteor.kcode.plugin.api.KcodeMessageCodec
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.session.HistoryConversationSession
import ai.meteor.kcode.session.HistoryConversationData
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object SessionHistoryProviderPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-session-history"
    override val inject = dependencies(KcodeHistory.Key, KcodeMessageCodec.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val codec = ctx.require(KcodeMessageCodec.Key).codec
        val history = ctx.require(KcodeHistory.Key).repository
        val sessions = mutableSetOf<ConversationSession>()
        val data = HistoryConversationData()
        var disposed = false
        KcodeSessions(ctx, ConversationSessionFactory { parent ->
            check(!disposed) { "Session provider has been disposed" }
            lateinit var session: ConversationSession
            session = HistoryConversationSession(history, parent, codec, data) { sessions.remove(session) }
            sessions += session
            session
        })
        effect.collect(Disposable {
            disposed = true
            val failures = mutableListOf<Throwable>()
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Throwable) {
                    failures += error
                }
            }
            sessions.clear()
            if (failures.isNotEmpty()) throw PluginCleanupException(name, failures)
        })
    }
}
