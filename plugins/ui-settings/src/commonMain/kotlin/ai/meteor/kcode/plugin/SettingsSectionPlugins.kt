package ai.meteor.kcode.plugin

import ai.meteor.kcode.localization.LocalTranslationCatalog
import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.plugin.ui.api.providerName
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import ai.meteor.kcode.plugin.ui.api.SettingsSectionRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.plugin.settings.LanguageSettings
import ai.meteor.kcode.plugin.settings.ShellExecutionSettings
import ai.meteor.kcode.plugin.settings.shellModeTitle
import androidx.compose.runtime.Composable
import org.cordis.dependencies
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.plugin

fun settingsSectionPlugin(section: SettingsSection): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.ui.settings.${section.id}", "builtin", "built-in", setOf("uiSlots", "settings.section")),
    plugin<Unit>(name = "settings-${section.id}", inject = dependencies(KcodeUiSlots.Key)) { ctx, _ ->
        collect(ctx.require(KcodeUiSlots.Key).registerSettings(section))
    },
    Unit,
)

private object LanguageSettingsRenderer : UiRenderer<SettingsSectionRequest> {
    @Composable
    override fun Render(request: SettingsSectionRequest) {
        LanguageSettings(
            language = LocalTranslationCatalog.current?.snapshot()?.selectLanguage(request.page.appSettings.language) ?: return,
            onLanguageChange = { request.page.onSettingsChange(request.page.appSettings.copy(language = it.code)) },
        )
    }
}

private object ShellSettingsRenderer : UiRenderer<SettingsSectionRequest> {
    @Composable
    override fun Render(request: SettingsSectionRequest) {
        ShellExecutionSettings(
            selected = ShellExecutionMode.fromCode(request.page.appSettings.shellExecutionMode) ?: ShellExecutionMode.App,
            onSelected = {
                request.page.onSettingsChange(request.page.appSettings.copy(shellExecutionMode = it.code))
            },
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
                            val selected = catalog.selectLanguage(it.appSettings.language)
                            catalog.languages.firstOrNull { option -> option.language == selected }?.displayNames?.let { names ->
                                names[LocalAppLanguage.current.code] ?: names["en"] ?: selected.code
                            }
                        }.orEmpty()
                    },
                    renderer = LanguageSettingsRenderer,
                ),
            ),
        )
    }
}

object DefaultModelSettingsSectionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "settings-model"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerSettings(
                SettingsSection(
                    id = "model", order = 20, icon = KcodeIconAsset.Model,
                    title = { text(UiText.ModelService) },
                    description = { it.current?.let { current -> providerName(current.provider) } ?: text(UiText.ModelProviderDescription) },
                    renderer = ModelSettingsRenderer,
                    isVisible = { it.modelCatalog.providers.isNotEmpty() },
                ),
            ),
        )
    }
}

object DefaultSearchSettingsSectionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "settings-search"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key, KcodeSearchSettings.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val policy = ctx.require(KcodeSearchSettings.Key).policy
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerSettings(
                SettingsSection(
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

object DefaultShellSettingsSectionPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "settings-shell"
    override val inject = dependencies(KcodeUiSlots.Key, KcodeLocalization.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(
            ctx.require(KcodeUiSlots.Key).registerSettings(
                SettingsSection(
                    id = "shell", order = 40, icon = KcodeIconAsset.Terminal,
                    title = { text(UiText.ShellExecution) },
                    description = { shellModeTitle(ShellExecutionMode.fromCode(it.appSettings.shellExecutionMode) ?: ShellExecutionMode.App) },
                    renderer = ShellSettingsRenderer,
                    isVisible = { it.shellSettingsAvailable },
                ),
            ),
        )
    }
}

fun defaultSettingsPlugins(): List<KcodePluginMount> = listOf(
    kcodePlugin(PluginDescriptor("provider.ui.settings.language", "builtin", "built-in", setOf("uiSlots", "settings.section")), DefaultLanguageSettingsSectionPlugin, Unit),
    kcodePlugin(PluginDescriptor("provider.ui.settings.model", "builtin", "built-in", setOf("uiSlots", "settings.section")), DefaultModelSettingsSectionPlugin, Unit),
    kcodePlugin(PluginDescriptor("provider.ui.settings.search", "builtin", "built-in", setOf("uiSlots", "settings.section")), DefaultSearchSettingsSectionPlugin, Unit),
    kcodePlugin(PluginDescriptor("provider.ui.settings.shell", "builtin", "built-in", setOf("uiSlots", "settings.section")), DefaultShellSettingsSectionPlugin, Unit),
)
