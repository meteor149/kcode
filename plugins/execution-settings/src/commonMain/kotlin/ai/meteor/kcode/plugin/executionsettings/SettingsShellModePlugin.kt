package ai.meteor.kcode.plugin.executionsettings

import ai.meteor.kcode.plugin.DefaultShellSettingsSectionPlugin
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeShellMode
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.ShellModePolicy
import ai.meteor.kcode.plugin.api.ShellModeSettingsPolicy
import ai.meteor.kcode.settings.ShellExecutionMode
import org.cordis.ConfigValidator
import ai.meteor.kcode.settings.SettingsMutationValidator
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Settings drive execution identity; missing settings suspend this policy and its consumers. */
object SettingsShellModePlugin : Plugin<Unit> {
    override val name = "settings-shell-mode"
    override val config = ConfigValidator<Unit> { it }
    override val inject = dependencies(KcodeSettings.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val store = ctx.require(KcodeSettings.Key).store
        val owner = PluginOperationOwner(name)
        val settingsPolicy = StoredShellModeSettingsPolicy()
        effect.collect(ctx.require(KcodeSettings.Key).mutations.register("feature.execution-settings", SettingsMutationValidator { previous, candidate ->
            settingsPolicy.resolve(candidate)
            val before = previous.namespaces["feature.execution-settings"]?.get("mode")
            val after = candidate.namespaces["feature.execution-settings"]?.get("mode")
            if (before != after && after != null) {
                require(after is JsonPrimitive && after.isString && ShellExecutionMode.fromCode(after.content) != null) {
                    "Permission/execution mode is not supported"
                }
            }
        }))
        effect.collect {
            settingsPolicy.close()
            owner.close()
        }
        KcodeShellMode(ctx, object : ShellModePolicy {
            override val settings: ShellModeSettingsPolicy? get() = settingsPolicy.takeIf { it.isActive }
            override suspend fun mode(): ShellExecutionMode = owner.run { settingsPolicy.resolve(store.load()) }
        })
        val settingsSection = ctx.plugin(DefaultShellSettingsSectionPlugin, Unit)
        effect.collect { settingsSection.dispose() }
    }
}
