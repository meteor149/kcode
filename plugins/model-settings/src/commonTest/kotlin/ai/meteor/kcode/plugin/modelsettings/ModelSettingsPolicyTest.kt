package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.modelId
import ai.meteor.kcode.test.modelApiKeys
import ai.meteor.kcode.test.dashscopeRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ModelSettingsPolicyTest {
    private val policy = CatalogModelSettingsPolicy(TemperatureRange(0.0, 1.0))
    @Test
    fun configuredTemperatureRangeAppliesToUiProposalsAndExplicitCommands() {
        val range = TemperatureRange(0.2, 0.4)
        val configured = CatalogModelSettingsPolicy(range)
        val settings = LegacySettings(provider = ModelProvider.OpenAI.name, modelId = "range-model")
        val catalog = ModelCatalogSnapshot(listOf(ModelProviderSpec(ModelProvider.OpenAI,
            listOf(ModelOption(ModelProvider.OpenAI, "range-model", defaultTemperature = 0.7)), 0)))
        val proposal = configured.update(settings, ModelConfiguration(
            ModelProvider.OpenAI, "range-model", "", temperature = 0.7,
        ))
        assertEquals(0.4, ModelSettingsDocument.read(proposal).temperature)
        assertFailsWith<IllegalArgumentException> {
            configured.update(settings, ModelConfiguration(ModelProvider.OpenAI, "range-model", "", temperature = Double.NaN))
        }
        assertFailsWith<IllegalArgumentException> {
            settings.applyModelSettingsUpdate(SettingsUpdate(mapOf("temperature" to "0.7")), catalog, range)
        }
        assertEquals(0.3, ModelSettingsDocument.read(settings.applyModelSettingsUpdate(
            SettingsUpdate(mapOf("temperature" to "0.3")), catalog, range,
        ).settings).temperature)
    }
    @Test
    fun absentProviderOrModelCannotSilentlySelectAnotherModel() {
        val settings = LegacySettings(provider = ModelProvider.DeepSeek.name, modelId = "plugin-model",
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
        val settings = LegacySettings(
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

        assertEquals("openai-key", ModelSettingsDocument.read(updated).modelApiKeys[ModelProvider.OpenAI.name])
        assertEquals("dashscope-key", ModelSettingsDocument.read(updated).modelApiKeys[ModelProvider.Alibaba.name])
        assertEquals(DashscopeRegion.ChinaMainland, policy.resolve(updated, ai.meteor.kcode.model.ModelCatalogSnapshot(listOf(
            ai.meteor.kcode.model.ModelProviderSpec(ModelProvider.Alibaba,
                listOf(ai.meteor.kcode.model.ModelOption(ModelProvider.Alibaba, "qwen3-max", defaultTemperature = 0.6)), 0),
        )))?.dashscopeRegion)
    }

}
