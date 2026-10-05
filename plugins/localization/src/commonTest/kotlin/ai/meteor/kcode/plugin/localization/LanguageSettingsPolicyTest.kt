package ai.meteor.kcode.plugin.localization

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.language
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LanguageSettingsPolicyTest {
    private fun document(value: String) = Json.parseToJsonElement(value) as JsonObject

    @Test
    fun featureDocumentPreservesUnknownDataAndOverridesLegacyPreference() {
        val catalog = DictionaryTranslationCatalog(resolveDictionaryConfiguration(Unit))
        val policy = checkNotNull(catalog.languageSettings)
        val future = document("""{"future":[null,{"key.with.dots":false}]}""")
        val stored = LegacySettings(language = "zh", namespaces = mapOf(
            "feature.localization" to future,
            "disabled.feature" to future,
        ))
        val saved = policy.update(stored, AppLanguage.English)
        assertEquals("zh", saved.language)
        assertEquals(AppLanguage.English, policy.preferredLanguage(saved))
        assertEquals(future["future"], saved.namespaces["feature.localization"]!!["future"])
        assertEquals(future, saved.namespaces["disabled.feature"])
        assertFailsWith<IllegalArgumentException> { policy.update(saved, AppLanguage("absent")) }
        catalog.close()
        assertNull(catalog.languageSettings)
        assertFailsWith<IllegalStateException> { policy.preferredLanguage(saved) }
        assertFailsWith<IllegalStateException> { policy.update(saved, AppLanguage.Chinese) }
    }

    @Test
    fun missingNamespaceImportsLegacyButEmptyNamespaceUsesDictionaryDefault() {
        val catalog = DictionaryTranslationCatalog(resolveDictionaryConfiguration(Unit))
        val policy = checkNotNull(catalog.languageSettings)
        val legacy = LegacySettings(language = "en")
        assertEquals(AppLanguage.English, policy.preferredLanguage(legacy))
        assertEquals(AppLanguage.Chinese, policy.preferredLanguage(legacy.copy(namespaces =
            mapOf("feature.localization" to JsonObject(emptyMap())))))
        val unknown = legacy.copy(namespaces = mapOf("feature.localization" to document("""{"language":"future.locale"}""")))
        assertEquals(AppLanguage.Chinese, policy.preferredLanguage(unknown))
        assertEquals(document("""{"language":"future.locale"}"""), unknown.namespaces["feature.localization"])
        catalog.close()
    }

    @Test
    fun ownedLanguageTypeIsStrictAndConfiguredDefaultBelongsToTheProvider() {
        val catalog = DictionaryTranslationCatalog(resolveDictionaryConfiguration(Json.parseToJsonElement(
            """{"defaultLanguage":"fr","languages":{"fr":"Français"}}""",
        )))
        val policy = checkNotNull(catalog.languageSettings)
        assertEquals(AppLanguage("fr"), policy.preferredLanguage(StoredAppSettings()))
        for (value in listOf("""{"language":null}""", """{"language":7}""")) {
            assertFailsWith<IllegalArgumentException> { policy.preferredLanguage(StoredAppSettings(namespaces =
                mapOf("feature.localization" to document(value)))) }
        }
        catalog.close()
    }
}
