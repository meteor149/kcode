package ai.meteor.kcode.plugin.agentloop

import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover

internal suspend fun authorizeToolCall(
    mode: ToolPermissionMode,
    request: ToolApprovalRequest,
    approver: ToolCallApprover,
): Boolean = when (mode) {
    ToolPermissionMode.Deny -> false
    ToolPermissionMode.Ask -> approver.approve(request)
    ToolPermissionMode.Bypass -> true
}
