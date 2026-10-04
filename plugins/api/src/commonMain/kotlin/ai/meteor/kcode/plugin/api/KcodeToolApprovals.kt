package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.tools.permission.ToolCallApprover
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

/** Approval presentation can be withdrawn independently of the interaction-mode policy. */
class KcodeToolApprovals(ctx: Context, val approver: ToolCallApprover) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeToolApprovals>("toolApprovals") }
}
