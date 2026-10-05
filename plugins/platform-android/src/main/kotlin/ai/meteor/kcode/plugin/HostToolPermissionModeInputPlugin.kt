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

/** Borrows a caller mode reader while approvals remain a replaceable SDK service. */
internal class HostToolPermissionModeInputPlugin : Plugin<suspend () -> ToolPermissionMode> {
    override val name = "kcode-host-tool-permission-mode-input"
    override val inject = dependencies(KcodeToolApprovals.Key)

    override suspend fun apply(ctx: Context, config: suspend () -> ToolPermissionMode, effect: EffectScope) {
        val approver = ctx.require(KcodeToolApprovals.Key).approver
        val owner = PluginOperationOwner(name)
        effect.collect { owner.close() }
        KcodeInteraction(ctx, InteractionPolicy(
            permissionModeProvider = { owner.run { config() } },
            approver = ToolCallApprover { request -> owner.run { approver.approve(request) } },
        ))
    }
}
