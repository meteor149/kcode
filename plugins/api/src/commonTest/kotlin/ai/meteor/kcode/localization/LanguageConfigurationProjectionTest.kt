package ai.meteor.kcode.localization

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LanguageConfigurationProjectionTest {
    @Test
    fun delegatesToFeatureConfigurationAndUsesDeclaredDefaultWithoutIt() {
        val custom = AppLanguage("private.locale")
        val settings = StoredAppSettings(legacyValues = JsonObject(mapOf("language" to JsonPrimitive("zh"))))
        var queries = 0
        val feature = object : LanguageSettingsPolicy {
            override fun preferredLanguage(settings: StoredAppSettings): AppLanguage {
                queries++
                return custom
            }
            override fun update(settings: StoredAppSettings, language: AppLanguage) = settings
        }
        val catalog = object : TranslationCatalog {
            override val available = MutableStateFlow(true)
            var configuration: LanguageSettingsPolicy? = feature
            override val languageSettings get() = configuration
            override fun snapshot(): LocalizationSnapshot? = if (available.value) LocalizationSnapshot(
                listOf(LanguageOption(custom, emptyMap()), LanguageOption(AppLanguage.English, emptyMap())),
                AppLanguage.English,
            ) else null
            override fun translate(language: AppLanguage, value: LocalizedText, vararg arguments: Any) = value.key
            override fun displayText(language: AppLanguage, value: LocalizedText, arguments: List<Any>) = value.key
        }
        assertEquals(custom, catalog.configuredLanguage(settings))
        assertEquals(1, queries)
        catalog.configuration = null
        assertEquals(AppLanguage.English, catalog.configuredLanguage(settings))
        assertEquals(1, queries)
        catalog.available.value = false
        assertFailsWith<IllegalArgumentException> { catalog.configuredLanguage(settings) }
    }
}
