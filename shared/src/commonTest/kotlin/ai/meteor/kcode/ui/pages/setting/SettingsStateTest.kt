package ai.meteor.kcode.ui.pages.setting

import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.settings.StoredAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStateTest {
    @Test
    fun savingAnotherProviderKeepsPreviouslyStoredKeys() {
        val settings = StoredAppSettings(
            provider = ModelProvider.OpenAI.name,
            modelApiKeys = mapOf(ModelProvider.OpenAI.name to "openai-key"),
        )

        val updated = settings.withConfiguration(
            ModelConfiguration(
                provider = ModelProvider.Alibaba,
                modelId = "qwen3-max",
                apiKey = "dashscope-key",
                dashscopeRegion = DashscopeRegion.ChinaMainland,
            ),
        )

        assertEquals("openai-key", updated.modelApiKeys[ModelProvider.OpenAI.name])
        assertEquals("dashscope-key", updated.modelApiKeys[ModelProvider.Alibaba.name])
        assertEquals(DashscopeRegion.ChinaMainland, updated.toModelConfiguration()?.dashscopeRegion)
    }

    @Test
    fun dashscopeRegionsUseTheirOpenAiCompatibleHosts() {
        assertEquals("https://dashscope.aliyuncs.com/", DashscopeRegion.ChinaMainland.baseUrl)
        assertEquals("https://dashscope-intl.aliyuncs.com/", DashscopeRegion.Singapore.baseUrl)
        assertEquals("https://dashscope-us.aliyuncs.com/", DashscopeRegion.UnitedStates.baseUrl)
    }
}
