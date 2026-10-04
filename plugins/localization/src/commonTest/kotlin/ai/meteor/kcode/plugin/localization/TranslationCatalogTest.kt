package ai.meteor.kcode.plugin.localization

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.UiText
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TranslationCatalogTest {
    @Test
    fun packageDictionaryFormatsExistingStringsAndRejectsRetiredReferences() {
        val catalog = DictionaryTranslationCatalog(resolveDictionaryConfiguration(Unit))
        assertEquals("New chat", catalog.translate(AppLanguage.English, UiText.NewChat))
        assertEquals("3 selected", catalog.translate(AppLanguage.English, UiText.SelectedMessages, 3))
        assertEquals("Saved to /private", catalog.translate(AppLanguage.English, UiText.ExportSaved, "/private"))
        assertEquals(AppLanguage.Chinese, catalog.snapshot()?.selectLanguage("unsupported"))
        assertFailsWith<IllegalArgumentException> { catalog.translate(AppLanguage.English, UiText.SelectedMessages) }
        assertFailsWith<IllegalArgumentException> { catalog.translate(AppLanguage.English, UiText.SelectedMessages, 1.5) }
        assertFailsWith<IllegalStateException> { catalog.translate(AppLanguage.English, LocalizedText("absent")) }
        catalog.close()
        assertFalse(catalog.available.value)
        assertNull(catalog.snapshot())
        assertNull(catalog.displayText(AppLanguage.English, UiText.NewChat, emptyList()))
        assertFailsWith<IllegalStateException> { catalog.translate(AppLanguage.English, UiText.NewChat) }
    }

    @Test
    fun configuredLanguageAndLabelsAreOwnedByTheActiveCatalog() {
        val catalog = DictionaryTranslationCatalog(resolveDictionaryConfiguration(Json.parseToJsonElement("""{
            "defaultLanguage":"fr", "languages":{"fr":"Français"},
            "translations":{"fr":{"new_chat":"Nouvelle conversation"},"en":{"new_chat":"Private action"}}
        }""")))
        val snapshot = checkNotNull(catalog.snapshot())
        assertEquals(AppLanguage("fr"), snapshot.selectLanguage("unsupported"))
        assertEquals("Nouvelle conversation", catalog.translate(AppLanguage("fr"), UiText.NewChat))
        assertEquals("Private action", catalog.translate(AppLanguage.English, UiText.NewChat))
        assertEquals("3 selected", catalog.translate(AppLanguage("fr"), UiText.SelectedMessages, 3))
        assertEquals("Français", snapshot.languages.last().displayNames["en"])
        assertEquals("100% 4", formatTranslation("100%% %1\$d", listOf(4)))
        val alternateFallback = DictionaryTranslationCatalog(resolveDictionaryConfiguration(Json.parseToJsonElement("""{
            "defaultLanguage":"fr", "fallbackLanguage":"zh", "languages":{"fr":"Français"}
        }""")))
        assertEquals("已选择 3 条", alternateFallback.translate(AppLanguage("fr"), UiText.SelectedMessages, 3))
    }

    @Test
    fun malformedConfigurationAndChangedArgumentContractsCannotMount() {
        for (raw in listOf(
            "[]", "null", """{"unknown":1}""", """{"defaultLanguage":"absent"}""",
            """{"languages":[]}""", """{"languages":{"bad code":"Name"}}""",
            """{"translations":{"fr":{"new_chat":"Label"}}}""",
            """{"translations":{"en":{"new_chat":1}}}""",
            """{"translations":{"en":{"new_chat":"%1${'$'}x"}}}""",
            """{"translations":{"en":{"selected_messages":"%2${'$'}d"}}}""",
            """{"translations":{"en":{"selected_messages":"%1${'$'}s %1${'$'}d"}}}""",
            """{"fallbackLanguage":"absent"}""",
        )) {
            assertFailsWith<Exception> { resolveDictionaryConfiguration(Json.parseToJsonElement(raw)) }
        }
    }
}
