package ai.meteor.kcode.plugin

import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.localization.LocalTranslationCatalog
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.localization.ui.LanguageSettings
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import ai.meteor.kcode.plugin.ui.api.SettingsSectionRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.ui.component.KcodeIconAsset
import androidx.compose.runtime.Composable
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

private object LanguageSettingsRenderer : UiRenderer<SettingsSectionRequest> {
    @Composable
    override fun Render(request: SettingsSectionRequest) {
        val policy = LocalTranslationCatalog.current?.languageSettings ?: return
        LanguageSettings(
            language = policy.preferredLanguage(request.page.appSettings),
            onLanguageChange = { request.page.onSettingsChange(policy.update(request.page.appSettings, it)) },
        )
    }
}

object DefaultLanguageSettingsSectionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "settings-language"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerSettings(
                SettingsSection(
                    id = "language", order = 10, icon = KcodeIconAsset.Language,
                    title = { text(UiText.Language) },
                    description = {
                        LocalTranslationCatalog.current?.snapshot()?.let { catalog ->
                            val selected = LocalTranslationCatalog.current?.languageSettings?.preferredLanguage(it.appSettings)
                                ?: catalog.defaultLanguage
                            catalog.languages.firstOrNull { option -> option.language == selected }?.displayNames?.let { names ->
                                names[LocalAppLanguage.current.code] ?: names["en"] ?: selected.code
                            }
                        }.orEmpty()
                    },
                    renderer = LanguageSettingsRenderer,
                    isVisible = { LocalTranslationCatalog.current?.languageSettings != null },
                ),
            ),
        )
    }
}
