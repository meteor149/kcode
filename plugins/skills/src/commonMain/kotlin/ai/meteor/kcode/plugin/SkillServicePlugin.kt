package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeSkills
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.skill.SkillReadRequest
import ai.meteor.kcode.skill.SkillRuntime
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

data class SkillServicePluginConfig(val runtime: SkillRuntime?)

object SkillServicePlugin : Plugin<SkillServicePluginConfig> {
    override val name = "kcode-skills"

    override suspend fun apply(ctx: Context, config: SkillServicePluginConfig, effect: EffectScope) {
        val owner = PluginOperationOwner("skills provider")
        effect.collect { owner.close() }
        KcodeSkills(ctx, config.runtime?.let { delegate ->
            object : SkillRuntime {
                override suspend fun catalog(forceReload: Boolean) = owner.run { delegate.catalog(forceReload) }
                override suspend fun prepareTurn(originalUserPrompt: String) = owner.run { delegate.prepareTurn(originalUserPrompt) }
                override suspend fun read(request: SkillReadRequest) = owner.run { delegate.read(request) }
            }
        })
    }
}
