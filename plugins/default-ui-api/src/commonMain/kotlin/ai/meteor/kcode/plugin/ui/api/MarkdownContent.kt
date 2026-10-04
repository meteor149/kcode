package ai.meteor.kcode.plugin.ui.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<String>) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

data class MarkdownPalette(val softInk: Color, val panel: Color, val rule: Color, val accent: Color)

data class MarkdownRenderRequest(
    val markdown: String,
    val compact: Boolean,
    val color: Color,
    val modifier: Modifier = Modifier,
    val onLongPressText: ((String, Int) -> Unit)? = null,
)

/** Immutable formatting computations; implementations and their renderers belong to providers. */
interface MarkdownContent {
    val palette: MarkdownPalette
    fun blocks(markdown: String): List<MarkdownBlock>
    fun inline(source: String, color: Color): AnnotatedString
    fun plainText(markdown: String): String
    @Composable
    fun Render(request: MarkdownRenderRequest)
}
