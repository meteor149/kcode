package ai.meteor.kcode.export

import ai.meteor.kcode.model.ChatMessage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.LayoutDirection

enum class ExportAction { Save, Share }

/** Caller captures an immutable message snapshot before starting an export. */
data class ConversationExportRequest(
    val conversationId: Long,
    val title: String,
    val messages: List<ChatMessage>,
    val selectedIds: Set<Long>? = null,
    val activeSecret: String = "",
    val truncatedLabel: String,
)

data class ConversationExportMessage(val isUser: Boolean, val content: String, val isError: Boolean)
data class ConversationImageRenderRequest(val title: String, val messages: List<ConversationExportMessage>, val truncatedLabel: String)
data class RenderedConversationImage(val image: ImageBitmap, val truncated: Boolean)
data class ConversationExportResult(val truncated: Boolean, val saved: ImageSaveResult)

/** Compose owns these resources; a provider must finish using them before its operation returns. */
interface ConversationImageRenderContext {
    val textMeasurer: TextMeasurer
    val graphicsLayer: GraphicsLayer
    val layoutDirection: LayoutDirection
}

data class ComposeConversationImageRenderContext(
    override val textMeasurer: TextMeasurer,
    override val graphicsLayer: GraphicsLayer,
    override val layoutDirection: LayoutDirection,
) : ConversationImageRenderContext

fun interface ConversationImageRenderer {
    suspend fun render(request: ConversationImageRenderRequest, context: ConversationImageRenderContext): RenderedConversationImage
}

interface ConversationExporter {
    /** Cancellation cancels rendering/saving; provider disposal cancels and joins owned operations. */
    suspend fun export(
        request: ConversationExportRequest,
        action: ExportAction,
        context: ConversationImageRenderContext,
    ): ConversationExportResult
}
