package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.anthropic.BuiltinModelLabels

object AnthropicModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Anthropic) {
    override fun createAdapter(): ModelAdapter = nativeAnthropicAdapter()
}

internal fun nativeAnthropicAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.Anthropic",
    supports = { it.provider == ModelProvider.Anthropic },
    create = ::createAnthropicModelRuntime,
    catalog = ModelProviderSpec(
        provider = ModelProvider.Anthropic,
        models = AnthropicModels,
        iconId = "Glm",
        order = 2,
        displayNames = labels("provider_anthropic_en", "provider_anthropic_zh"),
        descriptions = labels("provider_anthropic_note_en", "provider_anthropic_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = true,
            endpoint = false,
            region = false,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(),
    ),
)

private val AnthropicModels = listOf(
    ModelOption(ModelProvider.Anthropic, "claude-fable-5", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-opus-4-7", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-opus-4-6", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-opus-4-5", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-opus-4-1", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-opus-4-0", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-sonnet-4-6", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-sonnet-4-5", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-sonnet-4-0", 0.6),
    ModelOption(ModelProvider.Anthropic, "claude-haiku-4-5", 0.6),
)

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
