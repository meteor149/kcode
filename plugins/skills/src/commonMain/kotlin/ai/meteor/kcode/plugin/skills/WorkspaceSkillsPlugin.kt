package ai.meteor.kcode.plugin.skills

import ai.meteor.kcode.plugin.SkillServicePlugin
import ai.meteor.kcode.plugin.SkillServicePluginConfig
import ai.meteor.kcode.plugin.api.KcodeSkillWorkspace
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

/** Fresh discovery/materialization state for each concrete workspace generation. */
class WorkspaceSkillsPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "workspace-skills"
    override val inject = dependencies(KcodeSkillWorkspace.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val source = ctx.require(KcodeSkillWorkspace.Key)
        val runtime = createWorkspaceSkillRuntime(source.workspace, source.authorityId)
        SkillServicePlugin.apply(ctx, SkillServicePluginConfig(runtime), effect)
    }
}
