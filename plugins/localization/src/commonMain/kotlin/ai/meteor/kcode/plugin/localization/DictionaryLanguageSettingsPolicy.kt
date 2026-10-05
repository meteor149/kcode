package ai.meteor.kcode.plugin.localization

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LanguageSettingsPolicy
import ai.meteor.kcode.localization.LocalizationSnapshot
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class DictionaryLanguageSettingsPolicy(
    private val snapshot: LocalizationSnapshot,
    private val active: () -> Boolean,
) : LanguageSettingsPolicy {
    override fun preferredLanguage(settings: StoredAppSettings): AppLanguage {
        check(active()) { "Language settings policy has been disposed" }
        val document = settings.namespaces[Namespace]
        val code = (if (document == null) settings.legacyValues["language"] else document["language"])?.let { value ->
            require(value is JsonPrimitive && value.isString) { "Language preference must be a string" }
            value.content
        }.orEmpty()
        return snapshot.selectLanguage(code)
    }

    override fun update(settings: StoredAppSettings, language: AppLanguage): StoredAppSettings {
        check(active()) { "Language settings policy has been disposed" }
        require(snapshot.languages.any { it.language == language }) { "Language is not available" }
        val document = JsonObject(settings.namespaces[Namespace].orEmpty() +
            ("language" to JsonPrimitive(language.code)))
        return settings.copy(namespaces = settings.namespaces + (Namespace to document))
    }

    private companion object {
        const val Namespace = "feature.localization"
    }
}
