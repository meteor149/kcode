package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelConnectionDefaults
import ai.meteor.kcode.model.ModelConnectionRequirements
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelProviderSpec
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.ModelAdapter
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Each formal entry constructs its own catalog and client factory on every mount. */
abstract class NativeModelAdapterPlugin(val provider: ModelProvider) : Plugin<Unit> {
    final override val config = ConfigValidator<Unit> { it }
    final override val name = "kcode-llm-koog.${provider.name}"
    final override val inject = dependencies(KcodeLlm.Key)

    final override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        effect.collect(ctx.require(KcodeLlm.Key).register(nativeModelAdapter(provider)))
    }
}

object OpenAIModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.OpenAI)
object AzureOpenAIModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.AzureOpenAI)
object AnthropicModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Anthropic)
object GoogleModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Google)
object DeepSeekModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.DeepSeek)
object OpenRouterModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.OpenRouter)
object BedrockModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Bedrock)
object MistralModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Mistral)
object AlibabaModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Alibaba)
object OllamaModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.Ollama)
object GLMModelAdapterPlugin : NativeModelAdapterPlugin(ModelProvider.GLM)

internal fun nativeModelAdapter(provider: ModelProvider): ModelAdapter {
    val index = ModelProvider.entries.indexOf(provider)
    val available = modelProviderAvailable(provider)
    return ModelAdapter(
        id = "koog.${provider.name}",
        supports = { available && it.provider == provider },
        create = ::createAgentModelRuntime,
        catalog = if (!available) null else ModelProviderSpec(
            provider = provider,
            models = modelsFor(provider).map { it.withDefaultPresentation() },
            order = index,
            displayNames = providerDisplayNames(provider),
            descriptions = providerDescriptions(provider),
            requirements = ModelConnectionRequirements(
                apiKey = provider.requiresApiKey,
                endpoint = provider.requiresEndpoint,
                region = provider.requiresRegion,
                deployment = provider.requiresDeployment,
                dashscopeRegions = provider == ModelProvider.Alibaba,
            ),
            defaults = ModelConnectionDefaults(
                endpoint = if (provider == ModelProvider.Ollama) "http://localhost:11434" else "",
                region = if (provider == ModelProvider.Bedrock) "us-west-2" else "",
                apiVersion = if (provider == ModelProvider.AzureOpenAI) "2024-10-21" else "",
            ),
        ),
    )
}
