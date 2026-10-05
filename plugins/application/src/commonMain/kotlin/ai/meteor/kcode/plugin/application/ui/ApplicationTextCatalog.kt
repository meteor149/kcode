package ai.meteor.kcode.plugin.application.ui

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LanguageOption
import ai.meteor.kcode.localization.LanguageSettingsPolicy
import ai.meteor.kcode.localization.LocalizationSnapshot
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.TranslationCatalog
import ai.meteor.kcode.localization.formatLocalizedText
import ai.meteor.kcode.plugin.ui.api.UiTextDictionary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A root-owned rendering projection; it neither publishes nor closes a localization service. */
internal class ApplicationTextCatalog(
    private val primary: TranslationCatalog?,
    private val dictionaries: List<UiTextDictionary>,
) : TranslationCatalog {
    private val live = MutableStateFlow(true)
    override val available = live.asStateFlow()
    override val languageSettings: LanguageSettingsPolicy? get() = if (live.value) primary?.languageSettings else null

    override fun snapshot(): LocalizationSnapshot? {
        if (!live.value) return null
        return primary?.snapshot() ?: LocalizationSnapshot(
            languages = listOf(LanguageOption(AppLanguage.English, emptyMap())),
            defaultLanguage = AppLanguage.English,
        )
    }

    override fun translate(language: AppLanguage, value: LocalizedText, vararg arguments: Any): String {
        check(live.value) { "Application text catalog was withdrawn" }
        return displayText(language, value, arguments.toList()) ?: error("Missing UI text '${value.key}'")
    }

    override fun displayText(language: AppLanguage, value: LocalizedText, arguments: List<Any>): String? {
        if (!live.value) return null
        primary?.displayText(language, value, arguments)?.let { return it }
        val template = dictionaries.firstNotNullOfOrNull { dictionary ->
            if (dictionary.available.value) dictionary.values[value.key] else null
        } ?: return null
        return formatLocalizedText(template, arguments)
    }

    fun close() { live.value = false }
}
