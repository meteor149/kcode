package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.uitexts.websearch.BuiltinUiTexts
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.plugin.searchsettings.ui.SearchSettingsRenderer
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import ai.meteor.kcode.ui.component.KcodeIconAsset
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

object DefaultSearchSettingsSectionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "settings-search"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeSearchSettings.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val policy = ctx.require(KcodeSearchSettings.Key).policy
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerSettings(
                SettingsSection(
                    texts = BuiltinUiTexts,
                    id = "search", order = 30, icon = KcodeIconAsset.Search,
                    title = { text(UiText.InternetSearch) },
                    description = { request ->
                        policy.providers()?.let { providers ->
                            val current = policy.resolve(request.appSettings)
                            providers.firstOrNull { it.id == current.provider }?.displayName
                        }.orEmpty()
                    },
                    renderer = SearchSettingsRenderer(policy),
                ),
            ),
        )
    }
}
