package ai.meteor.kcode.plugin

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.KcodeTools
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

object ToolsServicePlugin : Plugin<Unit> {
    override val name = "kcode-tools"

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        KcodeTools(ctx)
    }
}

fun toolContributionPlugin(id: String, tools: ToolRegistry): Plugin<Unit> = plugin(
    name = "kcode-tool-$id",
    inject = dependencies(KcodeTools.Key),
) { ctx, _ ->
    collect(ctx.require(KcodeTools.Key).register(id, tools))
}
