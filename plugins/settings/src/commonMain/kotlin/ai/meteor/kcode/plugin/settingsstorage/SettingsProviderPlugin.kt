package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.SettingsStoreFactory
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

object SettingsProviderPlugin : Plugin<AppSettingsStore?> {
    override val name = "kcode-settings-platform"
    override suspend fun apply(ctx: Context, config: AppSettingsStore?, effect: EffectScope) {
        if (config == null) {
            FactorySettingsProviderPlugin.apply(ctx, memorySettingsStoreFactory(), effect)
            return
        }
        val owner = PluginOperationOwner(name)
        effect.collect(Disposable { owner.close() })
        KcodeSettings(ctx, OwnedSettingsStore(config, owner))
    }
}

object FactorySettingsProviderPlugin : Plugin<SettingsStoreFactory> {
    override val name = "kcode-settings-owned"
    override suspend fun apply(ctx: Context, config: SettingsStoreFactory, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        val resource = withContext(NonCancellable) {
            owner.run { config.create() }.also { resource ->
                effect.collect {
                    owner.requireCanClose()
                    withContext(NonCancellable) {
                        val failures = mutableListOf<Throwable>()
                        runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
                        runCatching { resource.close() }.exceptionOrNull()?.let(failures::add)
                        if (failures.isNotEmpty()) throw PluginCleanupException(name, failures)
                    }
                }
            }
        }
        if (!effect.isActive) return
        KcodeSettings(ctx, OwnedSettingsStore(resource.store, owner))
    }
}
