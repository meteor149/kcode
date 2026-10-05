package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.uitexts.modelsettings.BuiltinUiTexts
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.api.KcodeModelSettings
import ai.meteor.kcode.plugin.modelsettings.ui.ModelSettingsRenderer
import ai.meteor.kcode.plugin.modelsettings.ModelSettingsDocument
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.LocalModelCatalog
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import ai.meteor.kcode.plugin.ui.api.providerName
import ai.meteor.kcode.ui.component.KcodeIconAsset
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

object DefaultModelSettingsSectionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "settings-model"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeModelSettings.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerSettings(
                SettingsSection(
                    texts = BuiltinUiTexts,
                    id = "model", order = 20, icon = KcodeIconAsset.Model,
                    title = { text(UiText.ModelService) },
                    description = { request ->
                        LocalModelCatalog.current.providers.firstOrNull { it.provider.id == ModelSettingsDocument.read(request.appSettings).provider }
                            ?.let { providerName(it.provider) } ?: text(UiText.ModelProviderDescription)
                    },
                    renderer = ModelSettingsRenderer(ctx.require(KcodeModelSettings.Key).policy),
                    isVisible = { LocalModelCatalog.current.providers.isNotEmpty() },
                ),
            ),
        )
    }
}
