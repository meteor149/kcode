package ai.meteor.kcode.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatModelsTest {
    @Test
    fun titleIsNormalizedAndTruncated() {
        assertEquals("你好 世界", conversationTitle("  你好   世界  "))
        assertEquals("", conversationTitle("   "))
        assertTrue(conversationTitle("这是一个非常长的对话标题，它应当被安全截断并显示省略号").endsWith("…"))
    }

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
