package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.skill.SkillRuntime
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

data class SkillServicePluginConfig(val runtime: SkillRuntime?)

object SkillServicePlugin : Plugin<SkillServicePluginConfig> {
    override val name = "kcode-skills"

    override suspend fun apply(ctx: Context, config: SkillServicePluginConfig, effect: EffectScope) {
        KcodeSkills(ctx, config.runtime)
    }
}
