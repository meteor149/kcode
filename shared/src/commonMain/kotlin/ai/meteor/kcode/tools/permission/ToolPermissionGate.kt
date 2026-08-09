package ai.meteor.kcode.tools.permission

import ai.meteor.kcode.settings.ToolPermissionMode

data class ToolApprovalRequest(
    val name: String,
    val input: String,
    val description: String,
)

fun interface ToolCallApprover {
    suspend fun approve(request: ToolApprovalRequest): Boolean
}

internal suspend fun authorizeToolCall(
    mode: ToolPermissionMode,
    request: ToolApprovalRequest,
    approver: ToolCallApprover,
): Boolean = when (mode) {
    ToolPermissionMode.Deny -> false
    ToolPermissionMode.Ask -> approver.approve(request)
    ToolPermissionMode.Bypass -> true
}
