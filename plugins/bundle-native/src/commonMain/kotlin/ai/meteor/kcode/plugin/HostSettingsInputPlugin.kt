package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.SettingsStoreFactory
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Binds explicit caller inputs without selecting storage, defaults, paths or codecs. */
internal object HostSettingsInputPlugin : Plugin<SettingsStoreFactory> {
    override val name = "kcode-host-settings-input"

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
        KcodeSettings(ctx, object : AppSettingsStore {
            override val protection get(): SettingsProtection {
                owner.requireOpen()
                return resource.store.protection
            }
            override suspend fun load(): StoredAppSettings = owner.run { resource.store.load() }
            override suspend fun save(settings: StoredAppSettings) = owner.run { resource.store.save(settings) }
        })
    }
}
