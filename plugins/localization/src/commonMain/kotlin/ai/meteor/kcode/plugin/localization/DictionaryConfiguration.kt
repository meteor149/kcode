package ai.meteor.kcode.plugin.localization

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LanguageOption
import ai.meteor.kcode.localization.LocalizationSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class DictionaryConfiguration(
    val snapshot: LocalizationSnapshot,
    val translations: Map<String, Map<String, String>>,
)

internal fun resolveDictionaryConfiguration(value: Any?): DictionaryConfiguration {
    if (value is DictionaryConfiguration) return value
    val fields = when (value) {
        Unit -> JsonObject(emptyMap())
        is JsonObject -> value
        else -> error("Localization configuration must be an object")
    }
    require(fields.keys.all { it in setOf("defaultLanguage", "fallbackLanguage", "languages", "translations") }) {
        "Unknown localization configuration field"
    }
    fun string(value: Any?, name: String): String {
        require(value is JsonPrimitive && value.isString) { "$name must be a string" }
        return value.content
    }
    fun locale(code: String): AppLanguage {
        require(Regex("[A-Za-z][A-Za-z0-9_-]*").matches(code)) { "Invalid locale code" }
        return AppLanguage(code)
    }
    val languages = linkedMapOf(
        "zh" to LanguageOption(AppLanguage.Chinese, mapOf(
            "en" to BuiltinTranslations.getValue("simplified_chinese_en"),
            "zh" to BuiltinTranslations.getValue("simplified_chinese_zh"),
        )),
        "en" to LanguageOption(AppLanguage.English, mapOf(
            "en" to BuiltinTranslations.getValue("english_en"),
            "zh" to BuiltinTranslations.getValue("english_zh"),
        )),
    )
    fields["languages"]?.let { raw ->
        require(raw is JsonObject) { "languages must be an object" }
        raw.forEach { (code, name) ->
            val label = string(name, "Language name")
            require(label.isNotBlank()) { "Language name must not be empty" }
            languages[code] = LanguageOption(locale(code), mapOf(code to label, "en" to label))
        }
    }
    val translations = mutableMapOf<String, Map<String, String>>()
    for (code in listOf("en", "zh")) {
        translations[code] = BuiltinTranslations.filterKeys { it.endsWith("_$code") }
            .mapKeys { (key, _) -> key.removeSuffix("_$code") }
    }
    fields["translations"]?.let { raw ->
        require(raw is JsonObject) { "translations must be an object" }
        raw.forEach { (code, values) ->
            require(code in languages) { "Translations require a declared language" }
            require(values is JsonObject) { "Language translations must be an object" }
            val overrides = values.mapValues { (key, rawText) ->
                require(key.isNotBlank()) { "Translation key must not be blank" }
                string(rawText, "Translation").also { template ->
                    validateTranslation(template)
                    translations["en"]?.get(key)?.let { original ->
                        require(translationArguments(template) == translationArguments(original)) {
                            "Translation must retain the key's argument contract"
                        }
                    }
                }
            }
            translations[code] = translations[code].orEmpty() + overrides
        }
    }
    translations.values.forEach { dictionary -> dictionary.values.forEach(::validateTranslation) }
    val defaultLanguage = fields["defaultLanguage"]?.let { locale(string(it, "defaultLanguage")) } ?: AppLanguage.Chinese
    require(defaultLanguage.code in languages) { "Default language must be declared" }
    val fallbackLanguage = fields["fallbackLanguage"]?.let { locale(string(it, "fallbackLanguage")) } ?: AppLanguage.English
    require(fallbackLanguage.code in languages) { "Fallback language must be declared" }
    return DictionaryConfiguration(LocalizationSnapshot(languages.values.toList(), defaultLanguage, listOf(fallbackLanguage)), translations.toMap())
}
