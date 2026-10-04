package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.export.ConversationExportRequest
import ai.meteor.kcode.export.ConversationImageRenderContext
import ai.meteor.kcode.export.ConversationImageRenderRequest
import ai.meteor.kcode.export.ConversationImageRenderer
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ExportAction
import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.export.RenderedConversationImage
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ConversationExportLifecycleTest {
    private val image = object : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace = ColorSpaces.Srgb
        override val hasAlpha = true
        override val config = ImageBitmapConfig.Argb8888
        override fun prepareToDraw() = error("The exporter must forward the image without drawing it")
        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = error("The exporter must forward the image without reading its pixels")
    }

    private val context = object : ConversationImageRenderContext {
        override val textMeasurer: TextMeasurer get() = error("Custom renderer does not measure text")
        override val graphicsLayer: GraphicsLayer get() = error("Custom renderer does not draw layers")
        override val layoutDirection = LayoutDirection.Ltr
    }
    private val request = ConversationExportRequest(
        42, "secret title", listOf(
            ChatMessage(1, MessageRole.User, "excluded"),
            ChatMessage(2, MessageRole.Assistant, "secret sk_old_key_123"),
        ), selectedIds = setOf(2), activeSecret = "secret", truncatedLabel = "truncated",
    )

    @Test
    fun customRendererReceivesSelectedRedactedSnapshotAndShareResult() = runTest {
        var rendered: ConversationImageRenderRequest? = null
        var savedName: String? = null
        val renderer = ConversationImageRenderer { request, _ ->
            rendered = request
            RenderedConversationImage(image, true)
        }
        val saver = object : ConversationImageSaver {
            override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult = error("Share must not save")
            override suspend fun share(image: ImageBitmap, fileName: String): ImageSaveResult {
                assertSame(this@ConversationExportLifecycleTest.image, image)
                savedName = fileName
                return ImageSaveResult.Shared
            }
        }
        val exporter = ImageConversationExporter(renderer, saver)
        val result = exporter.export(request, ExportAction.Share, context)
        assertEquals("•••• title", rendered?.title)
        assertEquals(listOf("•••• ••••"), rendered?.messages?.map { it.content })
        assertEquals("kcode-42.png", savedName)
        assertTrue(result.truncated)
        assertEquals(ImageSaveResult.Shared, result.saved)
        exporter.close()
    }

    @Test
    fun disposalWaitsForRendererCancellationAndRejectsOldExporter() = runTest {
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val renderer = ConversationImageRenderer { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { stopped = true }
        }
        val saver = object : ConversationImageSaver {
            override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult = error("Cancelled renderer must not save")
            override suspend fun share(image: ImageBitmap, fileName: String): ImageSaveResult = error("Cancelled renderer must not share")
        }
        val exporter = ImageConversationExporter(renderer, saver)
        val pending = backgroundScope.async { exporter.export(request, ExportAction.Save, context) }
        entered.await()
        exporter.close()
        assertTrue(stopped)
        assertTrue(pending.isCompleted)
        assertFailsWith<IllegalStateException> { exporter.export(request, ExportAction.Save, context) }
    }

    @Test
    fun disposalWaitsForSavingRollbackBeforeReturning() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saver = object : ConversationImageSaver {
            override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
            override suspend fun share(image: ImageBitmap, fileName: String) = error("Save must not share")
        }
        val exporter = ImageConversationExporter(ConversationImageRenderer { _, _ ->
            RenderedConversationImage(image, false)
        }, saver)
        val pending = backgroundScope.async { exporter.export(request, ExportAction.Save, context) }
        entered.await()
        val closing = async { exporter.close() }
        cleaning.await()
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        closing.await()
        pending.join()
        assertTrue(pending.isCancelled)
    }

    @Test
    fun exporterRejectsDisposalFromItsOwnRenderer() = runTest {
        lateinit var exporter: ImageConversationExporter
        val saver = object : ConversationImageSaver {
            override suspend fun save(image: ImageBitmap, fileName: String) = error("Renderer failed")
            override suspend fun share(image: ImageBitmap, fileName: String) = error("Renderer failed")
        }
        exporter = ImageConversationExporter(ConversationImageRenderer { _, _ ->
            exporter.close()
            error("Reentrant close must fail")
        }, saver)
        assertFailsWith<IllegalStateException> { exporter.export(request, ExportAction.Save, context) }
        exporter.close()
    }
}
