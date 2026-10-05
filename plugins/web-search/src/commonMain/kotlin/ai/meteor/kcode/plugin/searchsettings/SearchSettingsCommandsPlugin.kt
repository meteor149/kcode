package ai.meteor.kcode.plugin.searchsettings

import ai.meteor.kcode.plugin.api.KcodeSettingsCommands
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.plugin.api.SettingsUpdateTransform
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

internal object SearchSettingsCommandsPlugin : Plugin<Unit> {
    override val name = "settings-commands.searchsettings"
    override val config = ConfigValidator<Unit> { it }
    override val inject = dependencies(KcodeSettingsCommands.Key, KcodeSearchSettings.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val policy = ctx.require(KcodeSearchSettings.Key).policy
        effect.collect(ctx.require(KcodeSettingsCommands.Key).updates.register(
            "searchsettings",
            setOf("search-provider", "search-api-key"),
            SettingsUpdateTransform { settings, update, catalog -> settings.applySearchSettingsUpdate(update, policy) },
        ))
    }
}
