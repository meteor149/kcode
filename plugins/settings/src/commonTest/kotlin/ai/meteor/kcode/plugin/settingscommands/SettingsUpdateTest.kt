package ai.meteor.kcode.plugin.settingscommands

import ai.meteor.kcode.plugin.searchhttp.HttpSearchSettingsPolicy
import ai.meteor.kcode.plugin.modelsettings.applyModelSettingsUpdate
import ai.meteor.kcode.plugin.searchsettings.applySearchSettingsUpdate
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.provider
import ai.meteor.kcode.test.webSearchApiKey
import ai.meteor.kcode.test.exaSearchApiKey
import ai.meteor.kcode.test.searchApiKeys
import ai.meteor.kcode.test.webSearchProvider
import ai.meteor.kcode.test.language
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsUpdateTest {
    private val catalog = ai.meteor.kcode.model.ModelCatalogSnapshot(ModelProvider.entries.mapIndexed { index, provider ->
        ai.meteor.kcode.model.ModelProviderSpec(provider, listOf(ai.meteor.kcode.model.ModelOption(provider,
            when (provider) {
                ModelProvider.DeepSeek -> "deepseek-v4-pro"
                ModelProvider.Anthropic -> "claude-test"
                else -> "test-model"
            }, defaultTemperature = 0.6)), index)
    })
    private fun StoredAppSettings.applySettingsUpdate(update: SettingsUpdate, catalog: ai.meteor.kcode.model.ModelCatalogSnapshot = this@SettingsUpdateTest.catalog) =
        applySettingsUpdate(update, catalog, HttpSearchSettingsPolicy())

    private fun StoredAppSettings.applySettingsUpdate(
        update: SettingsUpdate,
        catalog: ai.meteor.kcode.model.ModelCatalogSnapshot,
        searchPolicy: ai.meteor.kcode.tools.search.SearchSettingsPolicy,
    ): ai.meteor.kcode.settings.AppliedSettingsUpdate {
        require(!update.isEmpty)
        val modelFields = update.suppliedFields.any { !it.startsWith("search-") }
        val modelResult = if (modelFields) applyModelSettingsUpdate(update, catalog)
            else ai.meteor.kcode.settings.AppliedSettingsUpdate(this, emptyList())
        val result = if (update.suppliedFields.any { it.startsWith("search-") })
            modelResult.settings.applySearchSettingsUpdate(update, searchPolicy) else modelResult
        return result.copy(changedFields = (update.suppliedFields + modelResult.changedFields + result.changedFields).distinct())
    }

    @Test
    fun searchCommandUsesTheCurrentCustomCatalogAndProviderStoragePolicy() {
        val route = "private.search:v2"
        val policy = object : ai.meteor.kcode.tools.search.SearchSettingsPolicy {
            override fun providers() = listOf(ai.meteor.kcode.tools.search.SearchProviderOption(route, "Private Search", true))
            override fun resolve(settings: StoredAppSettings) =
                ai.meteor.kcode.tools.search.SearchSettingsConfiguration(route, settings.searchApiKeys)
            override fun update(settings: StoredAppSettings, configuration: ai.meteor.kcode.tools.search.SearchSettingsConfiguration) =
                settings.copy(webSearchProvider = configuration.provider, searchApiKeys = configuration.apiKeys)
        }
        val previous = LegacySettings(webSearchApiKey = "legacy", exaSearchApiKey = "exa",
            searchApiKeys = mapOf("other.route" to "other"))
        val result = previous.applySettingsUpdate(SettingsUpdate(mapOf("search-provider" to route, "search-api-key" to "custom-key")),
            catalog, policy)
        assertEquals(route, result.settings.webSearchProvider)
        assertEquals(mapOf("other.route" to "other", route to "custom-key"), result.settings.searchApiKeys)
        assertEquals("legacy", result.settings.webSearchApiKey)
        assertEquals("exa", result.settings.exaSearchApiKey)
        assertEquals(listOf("search-provider", "search-api-key"), result.changedFields)
        assertFailsWith<IllegalArgumentException> {
            previous.applySettingsUpdate(SettingsUpdate(mapOf("search-provider" to "google")), catalog, policy)
        }
    }

    @Test
    fun customProviderUsesRegisteredIdAndPreservesOtherApiKeys() {
        val provider = ModelProvider("acme.gateway:v1")
        val customCatalog = ai.meteor.kcode.model.ModelCatalogSnapshot(listOf(
            ai.meteor.kcode.model.ModelProviderSpec(provider, listOf(ai.meteor.kcode.model.ModelOption(provider, "private-model", defaultTemperature = 0.6)), 0,
                displayName = "Acme Gateway"),
        ))
        val old = LegacySettings(modelApiKeys = mapOf("OpenAI" to "legacy"))
        val updated = old.applySettingsUpdate(SettingsUpdate(mapOf(
            "model-provider" to provider.id,
            "model" to "private-model",
            "model-api-key" to "fixture",
        )), customCatalog).settings
        assertEquals(provider.id, updated.modelString("provider"))
        assertEquals(mapOf("OpenAI" to "legacy", provider.id to "fixture"), updated.modelKeys())
        val searchOnly = updated.applySettingsUpdate(SettingsUpdate(mapOf("search-provider" to "google")),
            ai.meteor.kcode.model.ModelCatalogSnapshot()).settings
        assertEquals(provider.id, searchOnly.modelString("provider"))
        assertFailsWith<IllegalArgumentException> {
            updated.applySettingsUpdate(SettingsUpdate(mapOf("model-api-key" to "late")), ai.meteor.kcode.model.ModelCatalogSnapshot())
        }
    }

    @Test
    fun unloadedProviderCannotBeConfiguredThroughAdb() {
        assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applySettingsUpdate(SettingsUpdate(mapOf("model-provider" to "deepseek")),
                ai.meteor.kcode.model.ModelCatalogSnapshot())
        }
    }

    @Test
    fun updatesModelAndSearchSettingsWithoutReplacingUnspecifiedValues() {
        val original = LegacySettings(
            modelRegion = "preserved-region",
            language = "en",
        )

        val applied = original.applySettingsUpdate(
            SettingsUpdate(mapOf(
                "model-provider" to "deep_seek",
                "model" to "deepseek-v4-pro",
                "model-api-key" to "model-secret",
                "temperature" to "0.3",
                "search-provider" to "exa",
                "search-api-key" to "search-secret",
            )),
        )

        assertEquals(ModelProvider.DeepSeek.name, applied.settings.modelString("provider"))
        assertEquals("deepseek-v4-pro", applied.settings.modelString("modelId"))
        assertEquals("model-secret", applied.settings.modelKeys()[ModelProvider.DeepSeek.name])
        assertEquals(0.3, applied.settings.modelString("temperature").toDouble())
        assertEquals("exa", ai.meteor.kcode.plugin.searchhttp.HttpSearchSettingsPolicy().resolve(applied.settings).provider)
        assertEquals("search-secret", ai.meteor.kcode.plugin.searchhttp.HttpSearchSettingsPolicy().resolve(applied.settings).apiKeys["exa"])
        assertEquals("preserved-region", applied.settings.modelString("modelRegion"))
        assertEquals("en", applied.settings.language)
        assertTrue("model-api-key" in applied.changedFields)
        assertTrue("search-api-key" in applied.changedFields)
    }

    @Test
    fun changingProviderSelectsItsFirstSupportedModelWhenModelIsOmitted() {
        val updated = StoredAppSettings().applySettingsUpdate(
            SettingsUpdate(mapOf("model-provider" to "anthropic")),
        ).settings

        assertEquals(ModelProvider.Anthropic.name, updated.modelString("provider"))
        assertTrue(updated.modelString("modelId").startsWith("claude-"))
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
            StoredAppSettings().applySettingsUpdate(
                SettingsUpdate(mapOf("model-provider" to providerCode)),
            )
        }
    }

    @Test
    fun emptyApiKeyClearsOnlyTheSelectedModelProviderKey() {
        val original = LegacySettings(
            provider = ModelProvider.OpenAI.name,
            modelApiKeys = mapOf(
                ModelProvider.OpenAI.name to "remove-me",
                ModelProvider.Google.name to "keep-me",
            ),
        )

        val updated = original.applySettingsUpdate(
            SettingsUpdate(mapOf("model-api-key" to "")),
        ).settings

        assertFalse(ModelProvider.OpenAI.name in updated.modelKeys())
        assertEquals("keep-me", updated.modelKeys()[ModelProvider.Google.name])
    }

    @Test
    fun rejectsAProviderModelMismatchWithoutIncludingTheApiKeyInTheError() {
        val error = assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applySettingsUpdate(
                SettingsUpdate(mapOf(
                    "model-provider" to "anthropic",
                    "model" to "gpt-4o-mini",
                    "model-api-key" to "must-not-leak",
                )),
            )
        }

        assertFalse("must-not-leak" in error.message.orEmpty())
    }

    @Test
    fun rejectsSearchKeysForGoogleAndOutOfRangeTemperatures() {
        assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applySettingsUpdate(
                SettingsUpdate(mapOf("search-provider" to "google", "search-api-key" to "unused")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applySettingsUpdate(
                SettingsUpdate(mapOf("temperature" to "1.1")),
            )
        }
    }
}

private fun StoredAppSettings.modelString(field: String): String =
    checkNotNull(namespaces["feature.model-settings"]?.get(field)).jsonPrimitive.content

private fun StoredAppSettings.modelKeys(): Map<String, String> =
    checkNotNull(namespaces["feature.model-settings"]?.get("modelApiKeys")).jsonObject
        .mapValues { (_, value) -> value.jsonPrimitive.content }
