package ai.meteor.kcode.tools.permission

data class ToolApprovalRequest(
    val name: String,
    val input: String,
    val description: String,
)

fun interface ToolCallApprover {
    suspend fun approve(request: ToolApprovalRequest): Boolean
}
