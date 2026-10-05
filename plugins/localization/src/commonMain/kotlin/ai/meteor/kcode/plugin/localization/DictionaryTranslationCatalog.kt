package ai.meteor.kcode.plugin.localization

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalizationSnapshot
import ai.meteor.kcode.localization.LanguageSettingsPolicy
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.TranslationCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DictionaryTranslationCatalog(private val dictionary: DictionaryConfiguration) : TranslationCatalog {
    private val live = MutableStateFlow(true)
    override val available = live.asStateFlow()
    private val settingsPolicy = DictionaryLanguageSettingsPolicy(dictionary.snapshot) { live.value }
    override val languageSettings: LanguageSettingsPolicy? get() = if (live.value) settingsPolicy else null

    fun close() { live.value = false }

    override fun snapshot(): LocalizationSnapshot? = if (live.value) dictionary.snapshot.copy(
        languages = dictionary.snapshot.languages.map { it.copy(displayNames = it.displayNames.toMap()) },
        fallbackLanguages = dictionary.snapshot.fallbackLanguages.toList(),
    ) else null

    override fun translate(language: AppLanguage, value: LocalizedText, vararg arguments: Any): String {
        check(live.value) { "Translation catalog has been disposed" }
        return formatTranslation(template(language, value) ?: error("Missing translation key '${value.key}'"), arguments.toList())
    }

    override fun displayText(language: AppLanguage, value: LocalizedText, arguments: List<Any>): String? =
        if (live.value) template(language, value)?.let { formatTranslation(it, arguments) } else null

    private fun template(language: AppLanguage, value: LocalizedText): String? {
        val selected = dictionary.snapshot.selectLanguage(language.code)
        return dictionary.translations[selected.code]?.get(value.key)
            ?: dictionary.snapshot.fallbackLanguages.firstNotNullOfOrNull { dictionary.translations[it.code]?.get(value.key) }
    }
}
