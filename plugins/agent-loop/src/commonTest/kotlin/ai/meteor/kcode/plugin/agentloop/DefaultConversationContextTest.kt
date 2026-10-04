package ai.meteor.kcode.plugin.agentloop

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import kotlin.test.Test
import kotlin.test.assertTrue

class DefaultConversationContextTest {
    @Test
    fun contextKeepsRolesAndSkipsErrors() {
        val context = buildContext(
            listOf(
                ChatMessage(1, MessageRole.User, "你好"),
                ChatMessage(2, MessageRole.Assistant, "网络错误", isError = true),
                ChatMessage(3, MessageRole.Assistant, "你好，有什么可以帮你？"),
            ),
            "继续",
        )
        assertTrue("User: 你好" in context)
        assertTrue("Assistant: 你好，有什么可以帮你？" in context)
        assertTrue("网络错误" !in context)
        assertTrue(context.endsWith("User: 继续"))
    }
}
