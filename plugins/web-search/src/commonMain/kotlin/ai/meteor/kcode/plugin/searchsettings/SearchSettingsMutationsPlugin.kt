package ai.meteor.kcode.plugin.searchsettings

import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.searchhttp.SearchSettingsDocument
import ai.meteor.kcode.settings.SettingsMutationValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

internal object SearchSettingsMutationsPlugin : Plugin<Unit> {
    override val name = "settings-mutations.search"
    override val inject = dependencies(KcodeSettings.Key, KcodeSearchSettings.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val policy = ctx.require(KcodeSearchSettings.Key).policy
        val namespace = "feature.web-search"
        effect.collect(ctx.require(KcodeSettings.Key).mutations.register(namespace, SettingsMutationValidator { previous, candidate ->
            val values = SearchSettingsDocument.read(candidate)
            if (previous.namespaces[namespace]?.get("provider") != candidate.namespaces[namespace]?.get("provider")) {
                require(policy.providers().orEmpty().any { it.id == values.provider }) { "Search provider is not enabled" }
            }
        }))
    }
}
