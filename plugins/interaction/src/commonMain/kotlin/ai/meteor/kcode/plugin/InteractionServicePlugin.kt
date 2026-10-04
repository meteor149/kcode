package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.tools.permission.ToolCallApprover
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

data class InteractionPluginConfig(val policy: InteractionPolicy)

object InteractionServicePlugin : Plugin<InteractionPluginConfig> {
    override val name = "kcode-interaction"

    override suspend fun apply(ctx: Context, config: InteractionPluginConfig, effect: EffectScope) {
        val owner = PluginOperationOwner("interaction provider")
        effect.collect { owner.close() }
        KcodeInteraction(ctx, InteractionPolicy(
            permissionModeProvider = { owner.run { config.policy.permissionModeProvider() } },
            approver = ToolCallApprover { request -> owner.run { config.policy.approver.approve(request) } },
        ))
    }
}
