package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.llm.OpenAIModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.AzureOpenAIModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.AnthropicModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.GoogleModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.DeepSeekModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.OpenRouterModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.BedrockModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.MistralModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.AlibabaModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.OllamaModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.GLMModelAdapterPlugin
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

object LlmServicePlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-llm"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeLlm(ctx)
    }
}

/** The adapter and its selectable catalog have the same Cordis lifetime. */
fun modelAdapterPlugin(adapter: ModelAdapter): KcodePluginMount = kcodePlugin(
    PluginDescriptor("provider.llm.${adapter.id}", "builtin", "built-in", setOf("llm")),
    object : Plugin<Unit> {
        override val config = ConfigValidator<Unit> { it }
        override val name = "kcode-llm-${adapter.id}"
        override val inject: Dependencies = dependencies(KcodeLlm.Key)
        override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
            effect.collect(ctx.require(KcodeLlm.Key).register(adapter))
        }
    },
    Unit,
)

fun defaultModelAdapterPlugins(): List<KcodePluginMount> = listOf(
    OpenAIModelAdapterPlugin,
    AzureOpenAIModelAdapterPlugin,
    AnthropicModelAdapterPlugin,
    GoogleModelAdapterPlugin,
    DeepSeekModelAdapterPlugin,
    OpenRouterModelAdapterPlugin,
    BedrockModelAdapterPlugin,
    MistralModelAdapterPlugin,
    AlibabaModelAdapterPlugin,
    OllamaModelAdapterPlugin,
    GLMModelAdapterPlugin,
).map { entry ->
    kcodePlugin(
        PluginDescriptor("provider.llm.koog.${entry.provider.name}", "builtin", "built-in", setOf("llm")),
        entry,
        Unit,
    )
}
