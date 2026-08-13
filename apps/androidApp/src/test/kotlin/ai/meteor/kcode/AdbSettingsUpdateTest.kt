package ai.meteor.kcode

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.settings.StoredAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbSettingsUpdateTest {
    @Test
    fun updatesModelAndSearchSettingsWithoutReplacingUnspecifiedValues() {
        val original = StoredAppSettings(
            modelRegion = "preserved-region",
            language = "en",
        )

        val applied = original.applyAdbSettingsUpdate(
            AdbSettingsUpdate(
                modelProvider = "deep_seek",
                model = "deepseek-v4-pro",
                modelApiKey = "model-secret",
                temperature = "0.3",
                searchProvider = "exa",
                searchApiKey = "search-secret",
            ),
        )

        assertEquals(ModelProvider.DeepSeek.name, applied.settings.provider)
        assertEquals("deepseek-v4-pro", applied.settings.modelId)
        assertEquals("model-secret", applied.settings.modelApiKeys[ModelProvider.DeepSeek.name])
        assertEquals(0.3, applied.settings.temperature)
        assertEquals("exa", applied.settings.webSearchProvider)
        assertEquals("search-secret", applied.settings.exaSearchApiKey)
        assertEquals("preserved-region", applied.settings.modelRegion)
        assertEquals("en", applied.settings.language)
        assertTrue("model-api-key" in applied.changedFields)
        assertTrue("search-api-key" in applied.changedFields)
    }

    @Test
    fun changingProviderSelectsItsFirstSupportedModelWhenModelIsOmitted() {
        val updated = StoredAppSettings().applyAdbSettingsUpdate(
            AdbSettingsUpdate(modelProvider = "anthropic"),
        ).settings

        assertEquals(ModelProvider.Anthropic.name, updated.provider)
        assertTrue(updated.modelId.startsWith("claude-"))
    }

    @Test
    fun acceptsDocumentedProviderCodes() {
        val providerCodes = listOf(
            "openai",
            "azure_openai",
            "anthropic",
            "google",
            "deepseek",
            "openrouter",
            "bedrock",
            "mistral",
            "alibaba",
            "ollama",
            "glm",
        )

        providerCodes.forEach { providerCode ->
            StoredAppSettings().applyAdbSettingsUpdate(
                AdbSettingsUpdate(modelProvider = providerCode),
            )
        }
    }

    @Test
    fun emptyApiKeyClearsOnlyTheSelectedModelProviderKey() {
        val original = StoredAppSettings(
            provider = ModelProvider.OpenAI.name,
            modelApiKeys = mapOf(
                ModelProvider.OpenAI.name to "remove-me",
                ModelProvider.Google.name to "keep-me",
            ),
        )

        val updated = original.applyAdbSettingsUpdate(
            AdbSettingsUpdate(modelApiKey = ""),
        ).settings

        assertFalse(ModelProvider.OpenAI.name in updated.modelApiKeys)
        assertEquals("keep-me", updated.modelApiKeys[ModelProvider.Google.name])
    }

    @Test
    fun rejectsAProviderModelMismatchWithoutIncludingTheApiKeyInTheError() {
        val error = assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applyAdbSettingsUpdate(
                AdbSettingsUpdate(
                    modelProvider = "anthropic",
                    model = "gpt-4o-mini",
                    modelApiKey = "must-not-leak",
                ),
            )
        }

        assertFalse("must-not-leak" in error.message.orEmpty())
    }

    @Test
    fun rejectsSearchKeysForGoogleAndOutOfRangeTemperatures() {
        assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applyAdbSettingsUpdate(
                AdbSettingsUpdate(searchProvider = "google", searchApiKey = "unused"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applyAdbSettingsUpdate(
                AdbSettingsUpdate(temperature = "1.1"),
            )
        }
    }
}
