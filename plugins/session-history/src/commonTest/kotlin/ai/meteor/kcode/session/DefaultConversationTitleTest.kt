package ai.meteor.kcode.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultConversationTitleTest {
    @Test
    fun titleIsNormalizedAndTruncated() {
        assertEquals("你好 世界", conversationTitle("  你好   世界  "))
        assertEquals("", conversationTitle("   "))
        assertTrue(conversationTitle("这是一个非常长的对话标题，它应当被安全截断并显示省略号").endsWith("…"))
    }

}
