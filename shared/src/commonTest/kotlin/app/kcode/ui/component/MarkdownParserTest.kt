package app.kcode.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownParserTest {
    @Test
    fun exportAndConversationRendererShareStructuredMarkdownBlocks() {
        val blocks = parseMarkdownBlocks(
            """
            # Heading

            A **bold** paragraph.

            > Quoted text

            - first
            - second

            ```kotlin
            val answer = 42
            ```

            | Name | Value |
            | --- | --- |
            | answer | 42 |
            """.trimIndent(),
        )

        assertEquals(6, blocks.size)
        assertIs<MarkdownBlock.Heading>(blocks[0])
        assertIs<MarkdownBlock.Paragraph>(blocks[1])
        assertIs<MarkdownBlock.Quote>(blocks[2])
        assertIs<MarkdownBlock.ListBlock>(blocks[3])
        assertIs<MarkdownBlock.Code>(blocks[4])
        assertIs<MarkdownBlock.Table>(blocks[5])
        assertTrue(inlineMarkdown("A **bold** value", MarkdownAccent).spanStyles.isNotEmpty())
    }
}
