package ai.meteor.kcode.plugin.localization

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalizationSnapshot
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.TranslationCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DictionaryTranslationCatalog(private val dictionary: DictionaryConfiguration) : TranslationCatalog {
    private val live = MutableStateFlow(true)
    override val available = live.asStateFlow()

    fun close() { live.value = false }

    override fun snapshot(): LocalizationSnapshot? = if (live.value) dictionary.snapshot.copy(
        languages = dictionary.snapshot.languages.map { it.copy(displayNames = it.displayNames.toMap()) },
        fallbackLanguages = dictionary.snapshot.fallbackLanguages.toList(),
    ) else null

    override fun translate(language: AppLanguage, value: LocalizedText, vararg arguments: Any): String {
        check(live.value) { "Translation catalog has been disposed" }
        return resolve(language, value, arguments.toList())
    }

    override fun displayText(language: AppLanguage, value: LocalizedText, arguments: List<Any>): String? =
        if (live.value) resolve(language, value, arguments) else null

    private fun resolve(language: AppLanguage, value: LocalizedText, arguments: List<Any>): String {
        val selected = dictionary.snapshot.selectLanguage(language.code)
        val template = dictionary.translations[selected.code]?.get(value.key)
            ?: dictionary.snapshot.fallbackLanguages.firstNotNullOfOrNull { dictionary.translations[it.code]?.get(value.key) }
            ?: error("Missing translation key '${value.key}'")
        return formatTranslation(template, arguments)
    }
}
