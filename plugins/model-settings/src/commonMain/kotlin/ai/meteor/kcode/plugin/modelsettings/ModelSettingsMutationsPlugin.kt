package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.settings.SettingsMutationValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

internal class ModelSettingsMutationsPlugin(private val range: TemperatureRange) : Plugin<Unit> {
    override val name = "settings-mutations.model"
    override val inject = dependencies(KcodeSettings.Key, KcodeLlm.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val models = ctx.require(KcodeLlm.Key)
        val namespace = "feature.model-settings"
        effect.collect(ctx.require(KcodeSettings.Key).mutations.register(namespace, SettingsMutationValidator { previous, candidate ->
            val values = ModelSettingsDocument.read(candidate)
            val before = previous.namespaces[namespace] ?: previous.legacyValues
            val after = candidate.namespaces[namespace] ?: candidate.legacyValues
            if (before["temperature"] != after["temperature"]) {
                require(values.temperature.isFinite() && values.temperature in range.minimum..range.maximum) {
                    "Temperature is outside the configured range"
                }
            }
            if (before["provider"] != after["provider"] || before["modelId"] != after["modelId"]) {
                val catalog = models.catalog()
                val provider = catalog.providers.firstOrNull { it.provider.name == values.provider }?.provider
                require(provider != null) { "Model provider is not enabled" }
                require(catalog.modelOption(provider, values.modelId) != null) { "Model is not available for the selected provider" }
            }
            if (before["dashscopeRegion"] != after["dashscopeRegion"]) {
                require(DashscopeRegion.entries.any { it.code == values.dashscopeRegion }) { "Dashscope region is not supported" }
            }
        }))
    }
}
