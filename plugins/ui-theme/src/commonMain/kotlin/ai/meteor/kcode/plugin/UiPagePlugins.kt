package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.pages.ui.design.DefaultThemeRenderer
import ai.meteor.kcode.plugin.pages.ui.design.ResolvedTheme
import ai.meteor.kcode.plugin.pages.ui.design.resolveThemeConfiguration
import ai.meteor.kcode.plugin.ui.api.ApplicationSlots
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.UiTextDictionary
import ai.meteor.kcode.plugin.uitexts.uitheme.BuiltinUiTexts
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

object DefaultThemeUiPlugin : Plugin<Any?> {
    override val config = ConfigValidator<Any?> { resolveThemeConfiguration(it) }
    override val name = "provider.ui.theme"
    override val inject = dependencies(KcodeUiSlots.Key)
    override suspend fun apply(ctx: Context, config: Any?, effect: EffectScope) {
        effect.collect(ctx.require(KcodeUiSlots.Key).registerTexts(UiTextDictionary(name, BuiltinUiTexts)))
        val renderer = DefaultThemeRenderer(config as ResolvedTheme)
        effect.collect(ctx.require(KcodeUiSlots.Key).register(ApplicationSlots.Theme, renderer))
    }
}
