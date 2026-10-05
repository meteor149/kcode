package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.KcodeToolApprovals
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.tools.permission.ToolCallApprover
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

/** Borrows a caller approver while the independently loaded feature owns its settings. */
internal object HostToolApprovalsInputPlugin : Plugin<ToolCallApprover> {
    override val name = "kcode-host-tool-approvals-input"

    override suspend fun apply(ctx: Context, config: ToolCallApprover, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        effect.collect { owner.close() }
        KcodeToolApprovals(ctx, ToolCallApprover { request -> owner.run { config.approve(request) } })
    }
}
