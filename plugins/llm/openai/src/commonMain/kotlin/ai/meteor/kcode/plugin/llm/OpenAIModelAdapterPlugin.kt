package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.openai.BuiltinModelLabels

object OpenAIModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.OpenAI) {
    override fun createAdapter(): ModelAdapter = nativeOpenAIAdapter()
}

internal fun nativeOpenAIAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.OpenAI",
    supports = { it.provider == ModelProvider.OpenAI },
    create = ::createOpenAIModelRuntime,
    catalog = ModelProviderSpec(
        provider = ModelProvider.OpenAI,
        models = OpenAIModels.map { it.presented() },
        iconId = "OpenAI",
        order = 0,
        displayNames = labels("provider_openai_en", "provider_openai_zh"),
        descriptions = labels("provider_openai_note_en", "provider_openai_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = true,
            endpoint = false,
            region = false,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(),
    ),
)

private val OpenAIModels = listOf(
    ModelOption(ModelProvider.OpenAI, "gpt-5.5", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.5-pro", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.4", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.4-mini", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.4-nano", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.4-pro", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.3-codex", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.2", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.2-pro", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.2-codex", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.1", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.1-codex", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5.1-codex-max", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5-mini", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5-nano", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5-codex", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-5-pro", 0.6),
    ModelOption(ModelProvider.OpenAI, "o4-mini", 0.6),
    ModelOption(ModelProvider.OpenAI, "o3", 0.6),
    ModelOption(ModelProvider.OpenAI, "o3-mini", 0.6),
    ModelOption(ModelProvider.OpenAI, "o1", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-4.1", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-4.1-mini", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-4.1-nano", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-4o", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-4o-mini", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-audio", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-4o-audio-preview", 0.6),
    ModelOption(ModelProvider.OpenAI, "gpt-4o-mini-audio-preview", 0.6),
)

private fun ModelOption.presented(): ModelOption = when (id) {
    "gpt-4o-mini" -> copy(
        displayNames = labels("model_gpt4o_mini_en", "model_gpt4o_mini_zh"),
        descriptions = labels("model_gpt4o_mini_desc_en", "model_gpt4o_mini_desc_zh"),
    )
    "gpt-4o" -> copy(
        displayNames = labels("model_gpt4o_en", "model_gpt4o_zh"),
        descriptions = labels("model_gpt4o_desc_en", "model_gpt4o_desc_zh"),
    )
    else -> this
}

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
