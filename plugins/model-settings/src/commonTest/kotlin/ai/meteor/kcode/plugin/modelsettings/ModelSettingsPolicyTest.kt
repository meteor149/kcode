package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.settings.StoredAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelSettingsPolicyTest {
    private val policy = CatalogModelSettingsPolicy(TemperatureRange(0.0, 1.0))
    @Test
    fun absentProviderOrModelCannotSilentlySelectAnotherModel() {
        val settings = StoredAppSettings(provider = ModelProvider.DeepSeek.name, modelId = "plugin-model",
            modelApiKeys = mapOf(ModelProvider.DeepSeek.name to "fixture"))
        assertNull(policy.resolve(settings, ai.meteor.kcode.model.ModelCatalogSnapshot()))
        val catalog = ai.meteor.kcode.model.ModelCatalogSnapshot(listOf(
            ai.meteor.kcode.model.ModelProviderSpec(ModelProvider.DeepSeek,
                listOf(ai.meteor.kcode.model.ModelOption(ModelProvider.DeepSeek, "plugin-model", defaultTemperature = 0.6)), 0),
        ))
        assertEquals("plugin-model", policy.resolve(settings, catalog)?.modelId)
        assertNull(policy.resolve(settings.copy(modelId = "withdrawn-model"), catalog))
    }

    @Test
    fun savingAnotherProviderKeepsPreviouslyStoredKeys() {
        val settings = StoredAppSettings(
            provider = ModelProvider.OpenAI.name,
            modelApiKeys = mapOf(ModelProvider.OpenAI.name to "openai-key"),
        )

        val updated = policy.update(settings,
            ModelConfiguration(
                provider = ModelProvider.Alibaba,
                modelId = "qwen3-max",
                apiKey = "dashscope-key",
                dashscopeRegion = DashscopeRegion.ChinaMainland, temperature = 0.6,
            ),
        )

        assertEquals("openai-key", updated.modelApiKeys[ModelProvider.OpenAI.name])
        assertEquals("dashscope-key", updated.modelApiKeys[ModelProvider.Alibaba.name])
        assertEquals(DashscopeRegion.ChinaMainland, policy.resolve(updated, ai.meteor.kcode.model.ModelCatalogSnapshot(listOf(
            ai.meteor.kcode.model.ModelProviderSpec(ModelProvider.Alibaba,
                listOf(ai.meteor.kcode.model.ModelOption(ModelProvider.Alibaba, "qwen3-max", defaultTemperature = 0.6)), 0),
        )))?.dashscopeRegion)
    }

}
