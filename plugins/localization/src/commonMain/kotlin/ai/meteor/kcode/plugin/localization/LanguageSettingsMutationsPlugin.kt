package ai.meteor.kcode.plugin.localization

import ai.meteor.kcode.plugin.api.KcodeLocalization
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.settings.SettingsMutationValidator
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

internal object LanguageSettingsMutationsPlugin : Plugin<Unit> {
    override val name = "settings-mutations.language"
    override val inject = dependencies(KcodeSettings.Key, KcodeLocalization.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val catalog = ctx.require(KcodeLocalization.Key).catalog
        val policy = requireNotNull(catalog.languageSettings)
        val namespace = "feature.localization"
        effect.collect(ctx.require(KcodeSettings.Key).mutations.register(namespace, SettingsMutationValidator { previous, candidate ->
            policy.preferredLanguage(candidate)
            val before = previous.namespaces[namespace]?.get("language")
            val after = candidate.namespaces[namespace]?.get("language")
            if (before != after && after != null) {
                require(after is JsonPrimitive && after.isString && catalog.snapshot()?.languages.orEmpty().any { it.language.code == after.content }) {
                    "Language is not available"
                }
            }
        }))
    }
}
