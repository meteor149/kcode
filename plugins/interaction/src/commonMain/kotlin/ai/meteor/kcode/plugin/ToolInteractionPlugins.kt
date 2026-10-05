package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.KcodeToolApprovals
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies

class HostModeToolInteractionPlugin : Plugin<suspend () -> ToolPermissionMode> {
    override val name = "kcode-host-mode-tool-interaction"
    override val inject = dependencies(KcodeToolApprovals.Key)
    override suspend fun apply(ctx: Context, config: suspend () -> ToolPermissionMode, effect: EffectScope) =
        applyToolInteraction(ctx, effect, config)
}

private fun applyToolInteraction(ctx: Context, effect: EffectScope, mode: suspend () -> ToolPermissionMode) {
    val approver = ctx.require(KcodeToolApprovals.Key).approver
    val owner = PluginOperationOwner("tool interaction policy")
    effect.collect { owner.close() }
    KcodeInteraction(ctx, InteractionPolicy(
        permissionModeProvider = { owner.run { mode() } },
        approver = ToolCallApprover { request -> owner.run { approver.approve(request) } },
    ))
}
