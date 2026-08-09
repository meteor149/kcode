package ai.meteor.kcode.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelConfigurationTest {
    @Test
    fun everyProviderHasSelectableModels() {
        ModelProvider.entries.forEach { provider ->
            assertTrue(modelsFor(provider).isNotEmpty(), "$provider should expose at least one model")
        }
    }

    @Test
    fun modelLookupReturnsProviderMetadata() {
        val model = assertNotNull(modelOption(ModelProvider.DeepSeek, "deepseek-v4-pro"))
        assertEquals(ModelProvider.DeepSeek, model.provider)
        assertEquals("deepseek-v4-pro", model.id)
        assertEquals(0.3, model.defaultTemperature)
    }

    @Test
    fun configurationHasSafeRuntimeDefaults() {
        val configuration = ModelConfiguration(ModelProvider.OpenAI, "gpt-4o-mini", "key")
        assertEquals(0.6, configuration.temperature)
    }

    @Test
    fun localAndHostedProvidersExposeCorrectConnectionRequirements() {
        assertTrue(!ModelProvider.Ollama.requiresApiKey)
        assertTrue(ModelProvider.Ollama.requiresEndpoint)
        assertTrue(ModelProvider.AzureOpenAI.requiresDeployment)
        assertTrue(ModelProvider.Bedrock.requiresRegion)
        assertTrue(ModelProvider.Anthropic.requiresApiKey)
    }
}
