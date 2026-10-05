package ai.meteor.kcode.plugin.export.ui

import ai.meteor.kcode.export.ConversationExporter
import ai.meteor.kcode.export.ConversationExportRequest
import ai.meteor.kcode.export.ConversationImageRenderContext
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
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.ui.state.ConversationState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ChatExportState(
    private val exporter: ConversationExporter,
    private val renderContext: ConversationImageRenderContext,
    private val language: AppLanguage,
    private val localization: TranslationCatalog,
    private val labels: ChatExportLabels,
    private val scope: CoroutineScope,
    private val owner: PluginOperationOwner,
) {
    private val pageOwner = PluginOperationOwner("Conversation export page")
    var notice by mutableStateOf<String?>(null)
        private set
    var exporting by mutableStateOf(false)
        private set
    private var requestSerial = 0L

    suspend fun close() = pageOwner.close()

    fun export(
        action: ExportAction,
        conversation: ConversationState?,
        configuration: ModelConfiguration?,
        selectedIds: Set<Long>? = null,
    ): Job? {
        owner.requireOpen()
        pageOwner.requireOpen()
        val target = conversation ?: return null
        val messages = target.messages.toList()
        if (exporting || messages.none { selectedIds == null || it.id in selectedIds }) return null
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
        return scope.launch {
            try {
                owner.runIfOpen {
                    pageOwner.runIfOpen {
                        val outcome = try {
                            Result.success(exporter.export(request, action, renderContext))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                        exporting = false
                        notice = outcome.fold(
                            onSuccess = { exportResultNotice(it.saved, it.truncated, language, localization, labels.unknownError) },
                            onFailure = { exportResultNotice(ImageSaveResult.Failed(it.message), false, language, localization, labels.unknownError) },
                        )
                        delay(4_000)
                        if (requestSerial == serial) notice = null
                    }
                }
            } catch (cancelled: CancellationException) {
                if (requestSerial == serial) notice = null
                throw cancelled
            } finally {
                if (requestSerial == serial) exporting = false
            }
        }
    }
}

internal data class ChatExportLabels(
    val truncatedFooter: String,
    val exporting: String,
    val unknownError: String,
)

@Composable
internal fun rememberChatExportState(exporter: ConversationExporter, owner: PluginOperationOwner): ChatExportState {
    val graphicsContext = LocalGraphicsContext.current
    val textMeasurer = rememberTextMeasurer()
    val layoutDirection = LocalLayoutDirection.current
    val language = LocalAppLanguage.current
    val localization = checkNotNull(LocalTranslationCatalog.current)
    val scope = rememberCoroutineScope()
    val labels = ChatExportLabels(text(UiText.ExportTruncatedFooter), text(UiText.ExportingConversation), text(UiText.UnknownError))
    val resources = remember(exporter, owner, graphicsContext, textMeasurer, layoutDirection, language, labels, localization) {
        val layer = graphicsContext.createGraphicsLayer()
        val state = ChatExportState(exporter, ComposeConversationImageRenderContext(textMeasurer, layer, layoutDirection),
            language, localization, labels, scope, owner)
        state to layer
    }
    DisposableEffect(resources) {
        onDispose {
            scope.launch(NonCancellable) {
                try { resources.first.close() } finally { graphicsContext.releaseGraphicsLayer(resources.second) }
            }
        }
    }
    return resources.first
}
