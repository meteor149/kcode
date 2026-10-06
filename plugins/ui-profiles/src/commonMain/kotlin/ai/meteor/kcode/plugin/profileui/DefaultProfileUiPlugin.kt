package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.profiles.KcodeProfiles
import ai.meteor.kcode.plugin.managementui.ProfilePluginManagerScreen
import ai.meteor.kcode.plugin.managementui.profilePluginManagerText
import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.SettingsSection
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.ui.component.KcodeIconAsset
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Optional child waits for management/default UI; legacy and alternative roots remain independent. */
object DefaultProfileUiPlugin : Plugin<Unit> {
    override val name = "ui-profiles"
    override val config = ConfigValidator<Unit> { it }
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val pluginManager = ctx.plugin(PluginManagerSettingsContribution, Unit)
        effect.collect { pluginManager.dispose() }
    }
}

private object PluginManagerSettingsContribution : Plugin<Unit> {
    override val name = "settings-plugins"
    override val inject = dependencies(KcodeProfiles.Key, KcodeUiSlots.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val client = ctx.require(KcodeProfiles.Key).client
        effect.collect(ctx.require(KcodeUiSlots.Key).registerSettings(SettingsSection(
            id = "plugins",
            order = 50,
            icon = KcodeIconAsset.Settings,
            title = { profilePluginManagerText("title", LocalAppLanguage.current.code) },
            description = { profilePluginManagerText("description", LocalAppLanguage.current.code) },
            renderer = UiRenderer { request ->
                ProfilePluginManagerScreen(
                    client = client,
                    languageCode = LocalAppLanguage.current.code,
                    embeddedInSettings = true,
                )
            },
        )))
    }
}
