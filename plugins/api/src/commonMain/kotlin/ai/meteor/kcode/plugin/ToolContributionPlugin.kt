@file:JvmName("ToolsPluginsKt")

package ai.meteor.kcode.plugin

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.plugin.api.KcodeTools
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin

/** Generic SDK registration for an explicitly borrowed tool registry. */
fun toolContributionPlugin(id: String, tools: ToolRegistry): Plugin<Unit> = plugin(
    name = "kcode-tool-$id",
    inject = dependencies(KcodeTools.Key),
) { ctx, _ ->
    collect(ctx.require(KcodeTools.Key).register(id, tools))
}
