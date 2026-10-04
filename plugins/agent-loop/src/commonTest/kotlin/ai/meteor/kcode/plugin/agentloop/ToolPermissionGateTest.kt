package ai.meteor.kcode.plugin.agentloop

import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ToolPermissionGateTest {
    private val request =
        ToolApprovalRequest("write_file", "{\"path\":\"/workspace/a\"}", "Writes a file")

    @Test
    fun denyAndBypassNeverOpenAnApprovalPrompt() = runTest {
        var prompted = false
        val approver = ToolCallApprover { prompted = true; true }

        assertFalse(authorizeToolCall(ToolPermissionMode.Deny, request, approver))
        assertFalse(prompted)
        assertTrue(authorizeToolCall(ToolPermissionMode.Bypass, request, approver))
        assertFalse(prompted)
    }

    @Test
    fun askUsesTheApprovalDecision() = runTest {
        assertTrue(authorizeToolCall(ToolPermissionMode.Ask, request, ToolCallApprover { true }))
        assertFalse(authorizeToolCall(ToolPermissionMode.Ask, request, ToolCallApprover { false }))
    }
}
