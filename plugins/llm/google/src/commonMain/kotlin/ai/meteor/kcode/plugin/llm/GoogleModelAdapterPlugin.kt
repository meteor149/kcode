package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.google.BuiltinModelLabels

object GoogleModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Google) {
    override fun createAdapter(): ModelAdapter = nativeGoogleAdapter()
}

internal fun nativeGoogleAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.Google",
    supports = { it.provider == ModelProvider.Google },
    create = ::createGoogleModelRuntime,
    catalog = ModelProviderSpec(
        provider = ModelProvider.Google,
        models = GoogleModels,
        iconId = "Google",
        order = 3,
        displayNames = labels("provider_google_en", "provider_google_zh"),
        descriptions = labels("provider_google_note_en", "provider_google_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = true,
            endpoint = false,
            region = false,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(),
    ),
)

private val GoogleModels = listOf(
    ModelOption(ModelProvider.Google, "gemini-3.5-flash", 0.6),
    ModelOption(ModelProvider.Google, "gemini-3.1-pro-preview", 0.6),
    ModelOption(ModelProvider.Google, "gemini-3.1-flash-lite", 0.6),
    ModelOption(ModelProvider.Google, "gemini-3.1-flash-lite-preview", 0.6),
    ModelOption(ModelProvider.Google, "gemini-3-flash-preview", 0.6),
    ModelOption(ModelProvider.Google, "gemini-2.5-pro", 0.6),
    ModelOption(ModelProvider.Google, "gemini-2.5-flash", 0.6),
    ModelOption(ModelProvider.Google, "gemini-2.5-flash-lite", 0.6),
    ModelOption(ModelProvider.Google, "gemini-2.0-flash-lite-001", 0.6),
)

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
