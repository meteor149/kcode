package ai.meteor.kcode.plugin.history

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

object HistoryProviderPlugin : Plugin<ConversationHistoryRepository> {
    override val name = "kcode-history-platform"
    override suspend fun apply(ctx: Context, config: ConversationHistoryRepository, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        effect.collect(Disposable { owner.close() })
        KcodeHistory(ctx, OwnedHistoryRepository(config, owner))
    }
}

/** Owns allocation, calls, cancellation cleanup, and native resource release. */
object FactoryHistoryProviderPlugin : Plugin<HistoryRepositoryFactory> {
    override val name = "kcode-history-owned"
    override suspend fun apply(ctx: Context, config: HistoryRepositoryFactory, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        val allocated = withContext(NonCancellable) {
            owner.run { config.create() }.also { resource ->
                effect.collect(Disposable {
                    owner.requireCanClose()
                    withContext(NonCancellable) {
                        val failures = mutableListOf<Throwable>()
                        runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
                        runCatching { resource.close() }.exceptionOrNull()?.let(failures::add)
                        if (failures.isNotEmpty()) throw PluginCleanupException(name, failures)
                    }
                })
            }
        }
        if (!effect.isActive) return
        KcodeHistory(ctx, OwnedHistoryRepository(allocated.repository, owner))
    }
}
