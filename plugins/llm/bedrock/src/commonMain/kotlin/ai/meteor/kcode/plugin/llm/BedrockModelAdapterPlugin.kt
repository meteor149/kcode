package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelOption
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.llm.labels.bedrock.BuiltinModelLabels

object BedrockModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Bedrock) {
    override fun createAdapter(): ModelAdapter = nativeBedrockAdapter()
}

internal fun nativeBedrockAdapter(): ModelAdapter = ModelAdapter(
    id = "koog.Bedrock",
    supports = { bedrockAvailable() && it.provider == ModelProvider.Bedrock },
    create = ::createBedrockModelRuntime,
    catalog = if (!bedrockAvailable()) null else ModelProviderSpec(
        provider = ModelProvider.Bedrock,
        models = BedrockModels,
        iconId = "Glm",
        order = 6,
        displayNames = labels("provider_bedrock_en", "provider_bedrock_zh"),
        descriptions = labels("provider_bedrock_note_en", "provider_bedrock_note_zh"),
        requirements = ModelConnectionRequirements(
            apiKey = true,
            endpoint = false,
            region = true,
            deployment = false,
        ),
        defaults = ModelConnectionDefaults(region = "us-west-2"),
    ),
)

private val BedrockModels = listOf(
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-fable-5", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-opus-4-7", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-opus-4-6-v1", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-opus-4-5-20251101-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-opus-4-1-20250805-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-opus-4-20250514-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-sonnet-4-6", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-sonnet-4-5-20250929-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-sonnet-4-20250514-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.anthropic.claude-haiku-4-5-20251001-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.amazon.nova-premier-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.amazon.nova-pro-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.amazon.nova-lite-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.amazon.nova-micro-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "moonshotai.kimi-k2.5", 0.6),
    ModelOption(ModelProvider.Bedrock, "moonshot.kimi-k2-thinking", 0.6),
    ModelOption(ModelProvider.Bedrock, "minimax.minimax-m2.5", 0.6),
    ModelOption(ModelProvider.Bedrock, "openai.gpt-oss-120b-1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "openai.gpt-oss-20b-1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "google.gemma-3-27b-it", 0.6),
    ModelOption(ModelProvider.Bedrock, "google.gemma-3-12b-it", 0.6),
    ModelOption(ModelProvider.Bedrock, "google.gemma-3-4b-it", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-3-70b-instruct-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-2-90b-instruct-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-2-11b-instruct-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-2-3b-instruct-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-2-1b-instruct-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-1-405b-instruct-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-1-70b-instruct-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-1-8b-instruct-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-70b-instruct-v1:0", 0.6),
    ModelOption(ModelProvider.Bedrock, "us.meta.llama3-8b-instruct-v1:0", 0.6),
)

private fun labels(english: String, chinese: String): Map<String, String> = mapOf(
    "en" to BuiltinModelLabels.getValue(english),
    "zh" to BuiltinModelLabels.getValue(chinese),
)
