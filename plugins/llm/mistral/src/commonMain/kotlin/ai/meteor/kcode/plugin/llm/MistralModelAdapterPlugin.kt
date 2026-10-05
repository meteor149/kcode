package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.mistral.BuiltinModelLabels

object MistralModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Mistral) {
    override fun createAdapter(): ModelAdapter = nativeMistralAdapter()
}

internal fun nativeMistralAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.Mistral",
    supports = { it.provider == ModelProvider.Mistral },
    create = ::createMistralModelRuntime,
    catalog = ModelProviderSpec(
        provider = ModelProvider.Mistral,
        models = MistralModels,
        iconId = "DeepSeek",
        order = 7,
        displayNames = labels("provider_mistral_en", "provider_mistral_zh"),
        descriptions = labels("provider_mistral_note_en", "provider_mistral_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = true,
            endpoint = false,
            region = false,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(),
    ),
)

private val MistralModels = listOf(
    ModelOption(ModelProvider.Mistral, "mistral-large-latest", 0.6),
    ModelOption(ModelProvider.Mistral, "mistral-medium-latest", 0.6),
    ModelOption(ModelProvider.Mistral, "mistral-small-latest", 0.6),
    ModelOption(ModelProvider.Mistral, "magistral-medium-latest", 0.6),
    ModelOption(ModelProvider.Mistral, "codestral-latest", 0.6),
    ModelOption(ModelProvider.Mistral, "devstral-medium-latest", 0.6),
)

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
