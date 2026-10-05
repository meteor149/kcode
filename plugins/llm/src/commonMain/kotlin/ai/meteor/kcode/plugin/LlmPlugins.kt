package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.ModelAdapter
import ai.meteor.kcode.plugin.api.PluginDescriptor
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

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
