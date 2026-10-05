package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.plugin.api.KcodeSettingsCommands
import ai.meteor.kcode.plugin.api.KcodeModelSettings
import ai.meteor.kcode.plugin.api.SettingsUpdateTransform
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

internal class ModelSettingsCommandsPlugin(private val range: TemperatureRange) : Plugin<Unit> {
    override val name = "settings-commands.modelsettings"
    override val config = ConfigValidator<Unit> { it }
    override val inject = dependencies(KcodeSettingsCommands.Key, KcodeModelSettings.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeSettingsCommands.Key).updates.register(
            "modelsettings",
            setOf("model-provider", "model", "model-api-key", "model-endpoint", "model-region", "model-deployment", "model-api-version", "dashscope-region", "temperature"),
            SettingsUpdateTransform { settings, update, catalog -> settings.applyModelSettingsUpdate(update, catalog, range) },
        ))
    }
}
