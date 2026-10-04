package ai.meteor.kcode.plugin.pages.chat



import ai.meteor.kcode.ui.state.ConversationState

import ai.meteor.kcode.export.ConversationExporter
import ai.meteor.kcode.export.ConversationExportRequest
import ai.meteor.kcode.export.ComposeConversationImageRenderContext
import ai.meteor.kcode.export.ExportAction
import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.TranslationCatalog
import ai.meteor.kcode.localization.LocalTranslationCatalog
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.model.ModelConfiguration
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

internal class ChatExportState(
    private val exporter: ConversationExporter?,
    private val graphicsLayer: GraphicsLayer,
    private val textMeasurer: TextMeasurer,
    private val layoutDirection: LayoutDirection,
    private val language: AppLanguage,
    private val localization: TranslationCatalog,
    private val labels: ChatExportLabels,
    private val scope: CoroutineScope,
) {
    var notice by mutableStateOf<String?>(null)
        private set
    private var exporting = false
    private var requestSerial = 0L

    fun export(
        action: ExportAction,
        conversation: ConversationState?,
        configuration: ModelConfiguration?,
        selectedIds: Set<Long>? = null,
    ) {
        val activeExporter = exporter ?: return
        val target = conversation ?: return
        val messages = target.messages.toList()
        if (exporting || messages.none { selectedIds == null || it.id in selectedIds }) return
        val request = ConversationExportRequest(
            conversationId = target.id,
            title = target.title,
            messages = messages,
            selectedIds = selectedIds?.toSet(),
            activeSecret = configuration?.apiKey.orEmpty(),
            truncatedLabel = labels.truncatedFooter,
        )
        val serial = ++requestSerial
        exporting = true
        notice = labels.exporting
        scope.launch {
            val outcome = try {
                Result.success(activeExporter.export(
                    request = request,
                    action = action,
                    context = ComposeConversationImageRenderContext(textMeasurer, graphicsLayer, layoutDirection),
                ))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                notice = null
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            } finally {
                exporting = false
            }
            notice = outcome.fold(
                onSuccess = { result -> exportResultNotice(result.saved, result.truncated, language, localization, labels.unknownError) },
                onFailure = { exportResultNotice(ImageSaveResult.Failed(it.message), false, language, localization, labels.unknownError) },
            )
            delay(4_000)
            if (requestSerial == serial) notice = null
        }
    }


}

internal data class ChatExportLabels(
    val truncatedFooter: String,
    val exporting: String,
    val unknownError: String,
)

@Composable
internal fun rememberChatExportState(exporter: ConversationExporter?): ChatExportState {
    val graphicsLayer = rememberGraphicsLayer()
    val textMeasurer = rememberTextMeasurer()
    val layoutDirection = LocalLayoutDirection.current
    val language = LocalAppLanguage.current
    val localization = checkNotNull(LocalTranslationCatalog.current)
    val scope = rememberCoroutineScope()
    val labels = ChatExportLabels(
        truncatedFooter = text(UiText.ExportTruncatedFooter),
        exporting = text(UiText.ExportingConversation),
        unknownError = text(UiText.UnknownError),
    )
    return remember(exporter, graphicsLayer, textMeasurer, layoutDirection, language, labels, localization) {
        ChatExportState(
            exporter = exporter,
            graphicsLayer = graphicsLayer,
            textMeasurer = textMeasurer,
            layoutDirection = layoutDirection,
            language = language,
            localization = localization,
            labels = labels,
            scope = scope,
        )
    }
}
