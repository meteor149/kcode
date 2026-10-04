package ai.meteor.kcode.plugin.settingscommands

import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.plugin.api.KcodeSettingsCommands
import ai.meteor.kcode.plugin.api.SettingsCommandHandler
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Configuration command policy belongs to a consumer, not an Android receiver or storage backend. */
object SettingsCommandsPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-settings-commands"
    override val inject = dependencies(KcodeSettings.Key, KcodeSearchSettings.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val store = ctx.require(KcodeSettings.Key).store
        val searchPolicy = ctx.require(KcodeSearchSettings.Key).policy
        val owner = PluginOperationOwner("Settings commands")
        val mutex = Mutex()
        effect.collect { owner.close() }
        KcodeSettingsCommands(ctx, SettingsCommandHandler { update, catalog ->
            owner.run {
                mutex.withLock {
                    val applied = store.load().applySettingsUpdate(update, catalog, searchPolicy)
                    store.save(applied.settings)
                    applied
                }
            }
        })
    }
}
