package app.kcode.tools

import app.kcode.settings.ToolPermissionMode
import app.kcode.tools.permission.ToolApprovalRequest
import app.kcode.tools.permission.ToolCallApprover
import app.kcode.tools.permission.authorizeToolCall
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
