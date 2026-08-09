package app.kcode.ui.pages.chat

import app.kcode.ui.state.ConversationState

import app.kcode.export.ConversationExportMessage
import app.kcode.export.ConversationImageSaver
import app.kcode.export.ImageSaveResult
import app.kcode.export.renderConversationImage
import app.kcode.localization.AppLanguage
import app.kcode.localization.LocalAppLanguage
import app.kcode.localization.UiText
import app.kcode.localization.resolveText
import app.kcode.localization.text
import app.kcode.model.MessageRole
import app.kcode.model.ChatMessage
import app.kcode.model.ModelConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class ExportAction { Save, Share }

internal fun redactExportSecrets(content: String, activeSecret: String): String {
    val currentKeyRedacted = if (activeSecret.isBlank()) content else content.replace(activeSecret, "••••")
    return Regex("sk[-_][A-Za-z0-9_-]{6,}", RegexOption.IGNORE_CASE)
        .replace(currentKeyRedacted, "••••")
}

internal fun messagesForExport(
    messages: List<ChatMessage>,
    selectedIds: Set<Long>?,
): List<ChatMessage> =
    if (selectedIds == null) messages.toList() else messages.filter { it.id in selectedIds }

internal class ChatExportState(
    private val imageSaver: ConversationImageSaver,
    private val graphicsLayer: GraphicsLayer,
    private val textMeasurer: TextMeasurer,
    private val layoutDirection: LayoutDirection,
    private val language: AppLanguage,
    private val labels: ChatExportLabels,
    private val scope: CoroutineScope,
) {
    var notice by mutableStateOf<String?>(null)
        private set
    private var exporting = false

    fun export(
        action: ExportAction,
        conversation: ConversationState?,
        configuration: ModelConfiguration?,
        selectedIds: Set<Long>? = null,
    ) {
        val target = conversation ?: return
        val messages = messagesForExport(target.messages, selectedIds)
        if (exporting || messages.isEmpty()) return
        exporting = true
        notice = labels.exporting
        scope.launch {
            val outcome = runCatching {
                val rendered = renderConversationImage(
                    textMeasurer = textMeasurer,
                    graphicsLayer = graphicsLayer,
                    layoutDirection = layoutDirection,
                    title = target.title,
                    messages = messages.map {
                        ConversationExportMessage(
                            isUser = it.role == MessageRole.User,
                            content = redactExportSecrets(it.content, configuration?.apiKey.orEmpty()),
                            isError = it.isError,
                        )
                    },
                    truncatedLabel = labels.truncatedFooter,
                )
                val fileName = "kcode-${target.id}.png"
                rendered to when (action) {
                    ExportAction.Save -> imageSaver.save(rendered.image, fileName)
                    ExportAction.Share -> imageSaver.share(rendered.image, fileName)
                }
            }
            notice = outcome.fold(
                onSuccess = { (rendered, result) -> resultMessage(rendered.truncated, result) },
                onFailure = { resolveText(language, UiText.ExportFailed, it.message ?: labels.unknownError) },
            )
            exporting = false
            delay(4_000)
            notice = null
        }
    }

    private suspend fun resultMessage(truncated: Boolean, result: ImageSaveResult): String = when (result) {
        is ImageSaveResult.Saved -> resolveText(
            language,
            if (truncated) UiText.ExportSavedTruncated else UiText.ExportSaved,
            result.location,
        )
        ImageSaveResult.Shared -> resolveText(language, UiText.ShareOpened)
        is ImageSaveResult.Failed -> resolveText(
            language,
            UiText.ExportFailed,
            result.reason ?: labels.unknownError,
        )
        ImageSaveResult.Unsupported -> labels.unsupported
    }
}

internal data class ChatExportLabels(
    val truncatedFooter: String,
    val exporting: String,
    val unsupported: String,
    val unknownError: String,
)

@Composable
internal fun rememberChatExportState(imageSaver: ConversationImageSaver): ChatExportState {
    val graphicsLayer = rememberGraphicsLayer()
    val textMeasurer = rememberTextMeasurer()
    val layoutDirection = LocalLayoutDirection.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val labels = ChatExportLabels(
        truncatedFooter = text(UiText.ExportTruncatedFooter),
        exporting = text(UiText.ExportingConversation),
        unsupported = text(UiText.ExportUnsupported),
        unknownError = text(UiText.UnknownError),
    )
    return remember(imageSaver, graphicsLayer, textMeasurer, layoutDirection, language, labels) {
        ChatExportState(
            imageSaver = imageSaver,
            graphicsLayer = graphicsLayer,
            textMeasurer = textMeasurer,
            layoutDirection = layoutDirection,
            language = language,
            labels = labels,
            scope = scope,
        )
    }
}
