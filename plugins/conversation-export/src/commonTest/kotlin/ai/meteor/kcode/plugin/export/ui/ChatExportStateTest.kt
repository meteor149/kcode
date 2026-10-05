package ai.meteor.kcode.plugin.export.ui

import ai.meteor.kcode.export.ConversationExportRequest
import ai.meteor.kcode.export.ConversationExportResult
import ai.meteor.kcode.export.ConversationExporter
import ai.meteor.kcode.export.ConversationImageRenderContext
import ai.meteor.kcode.export.ExportAction
import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.history.ConversationPresentation
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalizationSnapshot
import ai.meteor.kcode.localization.LocalizedText
import ai.meteor.kcode.localization.TranslationCatalog
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.ui.state.ConversationState
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatExportStateTest {
    private val renderContext = object : ConversationImageRenderContext {
        override val textMeasurer: TextMeasurer get() = error("The custom exporter must not measure text")
        override val graphicsLayer: GraphicsLayer get() = error("The custom exporter must not draw layers")
        override val layoutDirection = LayoutDirection.Ltr
    }
    private val catalog = object : TranslationCatalog {
        override val available = MutableStateFlow(true)
        override fun snapshot(): LocalizationSnapshot? = null
        override fun translate(language: AppLanguage, value: LocalizedText, vararg arguments: Any) = value.key
        override fun displayText(language: AppLanguage, value: LocalizedText, arguments: List<Any>) = value.key
    }

    @Test
    fun actionCapturesItsSelectionAndMessageSnapshotBeforeSuspending() = runTest {
        val owner = PluginOperationOwner("export UI")
        var captured: ConversationExportRequest? = null
        var capturedAction: ExportAction? = null
        val exporter = object : ConversationExporter {
            override suspend fun export(request: ConversationExportRequest, action: ExportAction, context: ConversationImageRenderContext): ConversationExportResult {
                captured = request; capturedAction = action
                return ConversationExportResult(false, ImageSaveResult.Shared)
            }
        }
        val state = ChatExportState(exporter, renderContext, AppLanguage.English, catalog,
            ChatExportLabels("truncated", "exporting", "unknown"), this, owner)
        val target = TestConversation()
        val selected = mutableSetOf(1L)
        val exporting = requireNotNull(state.export(ExportAction.Share, target, null, selected))
        target.title = "changed"
        target.messages.clear()
        selected.clear()
        exporting.join()
        assertEquals("original", captured?.title)
        assertEquals(listOf(1L), captured?.messages?.map { it.id })
        assertEquals(setOf(1L), captured?.selectedIds)
        assertEquals(ExportAction.Share, capturedAction)
        assertFalse(state.exporting)
        state.close(); owner.close()
    }

    @Test
    fun uiWithdrawalCancelsAndJoinsExporterCleanupThenRejectsOldActions() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = PluginOperationOwner("export UI")
        val exporter = object : ConversationExporter {
            override suspend fun export(request: ConversationExportRequest, action: ExportAction, context: ConversationImageRenderContext): ConversationExportResult {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val state = ChatExportState(exporter, renderContext, AppLanguage.English, catalog,
            ChatExportLabels("truncated", "exporting", "unknown"), backgroundScope, owner)
        val job = requireNotNull(state.export(ExportAction.Save, TestConversation(), null))
        entered.await()
        val closing = async { owner.close() }
        cleaning.await()
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        closing.await(); job.join()
        assertTrue(job.isCancelled)
        assertNull(state.notice)
        assertFailsWith<IllegalStateException> { state.export(ExportAction.Save, TestConversation(), null) }
        state.close()
    }

    @Test
    fun leavingThePageJoinsItsOperationWithoutClosingTheBorrowedExporter() = runTest {
        val entered = CompletableDeferred<Unit>()
        val owner = PluginOperationOwner("export UI")
        val exporter = object : ConversationExporter {
            override suspend fun export(request: ConversationExportRequest, action: ExportAction, context: ConversationImageRenderContext): ConversationExportResult {
                entered.complete(Unit); awaitCancellation()
            }
        }
        val state = ChatExportState(exporter, renderContext, AppLanguage.English, catalog,
            ChatExportLabels("truncated", "exporting", "unknown"), backgroundScope, owner)
        val job = requireNotNull(state.export(ExportAction.Save, TestConversation(), null))
        entered.await()
        state.close()
        job.join()
        assertTrue(job.isCancelled)
        assertFailsWith<IllegalStateException> { state.export(ExportAction.Save, TestConversation(), null) }
        owner.requireOpen()
        owner.close()
    }
}

private class TestConversation : ConversationState {
    override val id = 42L
    override var title = "original"
    override var isPinned = false
    override var goal: ThreadGoal? = null
    override var presentation = ConversationPresentation.Recent
    override var standaloneResult: String? = null
    override var shouldResumeGoal = false
    override val messages = mutableListOf(ChatMessage(1, MessageRole.User, "original"))
    override var executionFailure: String? = null
    override var isGenerating = false
    override var isAwaitingFirstToken = false
    override var runningJob: Job? = null
    override fun reserveMessageIds(count: Int): Long = error("Export cannot mutate message identities")
}
