package ai.meteor.kcode.plugin.provider

import ai.meteor.kcode.plugin.DefaultSearchSettingsSectionPlugin
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.plugin.searchhttp.HttpSearchSettingsPolicy
import ai.meteor.kcode.plugin.searchsettings.SearchSettingsCommandsPlugin
import ai.meteor.kcode.plugin.searchsettings.SearchSettingsMutationsPlugin
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

/** Configuration and UI children owned by the search feature. */
object SearchSettingsProviderPlugin : Plugin<Unit> {
    override val name = "provider.search-settings.http"
    override val config = ConfigValidator<Unit> { it }
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val policy = HttpSearchSettingsPolicy()
        effect.collect(Disposable { policy.close() })
        KcodeSearchSettings(ctx, policy)
        val mutations = ctx.plugin(SearchSettingsMutationsPlugin, Unit)
        effect.collect { mutations.dispose() }
        val commands = ctx.plugin(SearchSettingsCommandsPlugin, Unit)
        effect.collect { commands.dispose() }
        val settingsSection = ctx.plugin(DefaultSearchSettingsSectionPlugin, Unit)
        effect.collect { settingsSection.dispose() }
    }
}
