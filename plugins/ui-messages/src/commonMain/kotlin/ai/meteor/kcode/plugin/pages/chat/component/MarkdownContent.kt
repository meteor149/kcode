package ai.meteor.kcode.plugin.pages.chat.component

import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.MarkdownRenderRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
internal fun MarkdownText(
    markdown: String,
    compact: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onLongPressText: ((String, Int) -> Unit)? = null,
) {
    val content = LocalApplicationUiSlots.current.markdown
    key(content) { content?.Render(MarkdownRenderRequest(markdown, compact, color, modifier, onLongPressText)) }
}
