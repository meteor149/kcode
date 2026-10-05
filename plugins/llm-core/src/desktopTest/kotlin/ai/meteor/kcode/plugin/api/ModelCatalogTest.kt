package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.plugin.llmcore.OwnedLlmRegistry
import ai.koog.http.client.KoogHttpClient
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelProviderSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.cordis.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun registrationCopiesMetadataAndRevokesPreviouslyResolvedAdapter() = runBlocking {
        val registry = OwnedLlmRegistry(Context())
        val models = mutableListOf(ModelOption(ModelProvider.DeepSeek, "plugin-model", defaultTemperature = 0.6))
        val configuration = ModelConfiguration(ModelProvider.DeepSeek, "plugin-model", "fixture", temperature = 0.6)
        val registration = registry.register(adapter(models))
        val resolved = registry.resolve(configuration)
        models.clear()
        assertEquals("plugin-model", registry.catalog().modelsFor(ModelProvider.DeepSeek).single().id)
        registration.dispose()
        assertTrue(registry.catalog().providers.isEmpty())
        assertFalse(resolved.supports(configuration))
        assertFailsWith<IllegalStateException> { resolved.create(configuration, UnusedFactory) }
        assertFailsWith<IllegalStateException> { registry.resolve(configuration) }
        val replacement = registry.register(adapter(listOf(ModelOption(ModelProvider.DeepSeek, "replacement", defaultTemperature = 0.6))))
        registration.dispose()
        assertEquals("replacement", registry.catalog().modelsFor(ModelProvider.DeepSeek).single().id)
        replacement.dispose()
    }

    @Test
    fun duplicateProvidersAndInvalidModelsCannotChangeTheRegisteredCatalog() = runBlocking {
        val registry = OwnedLlmRegistry(Context())
        val registration = registry.register(adapter(listOf(ModelOption(ModelProvider.DeepSeek, "existing", defaultTemperature = 0.6))))
        try {
            val before = registry.catalog()
            assertFailsWith<IllegalArgumentException> {
                registry.register(adapter(listOf(ModelOption(ModelProvider.DeepSeek, "other", defaultTemperature = 0.6))).copy(id = "duplicate"))
            }
            assertFailsWith<IllegalArgumentException> {
                registry.register(adapter(listOf(ModelOption(ModelProvider.OpenAI, "wrong-provider", defaultTemperature = 0.6))).copy(id = "invalid"))
            }
            assertEquals(before, registry.catalog())
        } finally {
            registration.dispose()
        }
    }

    @Test
    fun customProviderMetadataAndConfigurationWithdrawAndRestore(): Unit = runBlocking {
        val provider = ModelProvider("acme.gateway:v1")
        val registry = OwnedLlmRegistry(Context())
        val contribution = ModelAdapter(
            id = "custom-route", supports = { it.provider == provider }, create = { _, _ -> error("No network in catalog test") },
            catalog = ModelProviderSpec(provider, listOf(ModelOption(provider, "private-model", defaultTemperature = 0.6)), 0,
                displayName = "Acme Gateway", description = "Private deployment"),
        )
        val configuration = ModelConfiguration(provider, "private-model", "fixture", temperature = 0.6)
        val oldRegistration = registry.register(contribution)
        val old = registry.resolve(configuration)
        assertEquals("Acme Gateway", registry.catalog().provider(provider)?.displayName)
        assertEquals("Private deployment", registry.catalog().provider(provider)?.description)
        oldRegistration.dispose()
        assertEquals(null, registry.catalog().provider(provider))
        assertFailsWith<IllegalStateException> { registry.resolve(configuration) }
        assertFailsWith<IllegalStateException> { old.create(ModelConfiguration(provider, "private-model", "fixture", temperature = 0.6), UnusedFactory) }
        val replacement = registry.register(contribution)
        oldRegistration.dispose()
        assertEquals(provider, registry.catalog().provider(provider)?.provider)
        replacement.dispose()
    }

    @Test
    fun connectionChoicesAreCopiedAndInvalidReplacementKeepsTheCatalog() = runBlocking {
        val registry = OwnedLlmRegistry(Context())
        val labels = mutableMapOf("en" to "Private zone")
        val choices = mutableListOf(ai.meteor.kcode.model.ModelConnectionChoice("private-zone", labels))
        val contribution = adapter(listOf(ModelOption(ModelProvider.DeepSeek, "fixture", 0.6))).let {
            it.copy(catalog = requireNotNull(it.catalog).copy(
                iconId = "custom.icon",
                defaults = ai.meteor.kcode.model.ModelConnectionDefaults(region = "private-zone"),
                regionChoices = choices,
                regionMigrationKey = "oldPrivateZone",
            ))
        }
        val registration = registry.register(contribution)
        try {
            labels["en"] = "changed"
            choices.clear()
            val committed = requireNotNull(registry.catalog().provider(ModelProvider.DeepSeek))
            assertEquals("Private zone", committed.regionChoices.single().displayNames["en"])
            assertEquals("custom.icon", committed.iconId)
            assertFailsWith<IllegalArgumentException> {
                registry.register(contribution.copy(id = "invalid", catalog = committed.copy(
                    regionChoices = listOf(committed.regionChoices.single(), committed.regionChoices.single()),
                )))
            }
            assertEquals(committed, registry.catalog().provider(ModelProvider.DeepSeek))
        } finally { registration.dispose() }
        assertTrue(registry.catalog().providers.isEmpty())
    }

    @Test
    fun registryWithdrawalRejectsStaleHandlesAndSupportsCanInspectTheRegistry() = runBlocking {
        val registry = OwnedLlmRegistry(Context())
        val config = ModelConfiguration(ModelProvider.DeepSeek, "model", "fixture", 0.6)
        val registration = registry.register(adapter(listOf(ModelOption(ModelProvider.DeepSeek, "model", 0.6))).copy(
            supports = { runBlocking { registry.adapterIds().isNotEmpty() } },
        ))
        val retained = registry.resolve(config)
        registry.close()
        assertFalse(retained.supports(config))
        assertFailsWith<IllegalStateException> { retained.create(config, UnusedFactory) }
        assertFailsWith<IllegalStateException> { registry.catalog() }
        assertFailsWith<IllegalStateException> { registry.register(adapter(listOf(ModelOption(ModelProvider.DeepSeek, "new", 0.6)))) }
        registration.dispose()
        registry.close()
    }

    private fun adapter(models: List<ModelOption>) = ModelAdapter(
        id = "fixture", supports = { it.provider == ModelProvider.DeepSeek },
        create = { _, _ -> error("Test must not construct a client") },
        catalog = ModelProviderSpec(ModelProvider.DeepSeek, models, 0),
    )
}

private object UnusedFactory : KoogHttpClient.Factory {
    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient = error("Disposed adapter must not reach the factory")
}
