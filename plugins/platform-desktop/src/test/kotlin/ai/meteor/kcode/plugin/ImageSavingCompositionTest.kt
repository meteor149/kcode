package ai.meteor.kcode.plugin

import ai.meteor.kcode.export.ConversationExporter
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
import ai.meteor.kcode.plugin.api.ConversationImageSaverResource
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeConversationExport
import ai.meteor.kcode.plugin.api.KcodeConversationImageSaving
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin

class ImageSavingCompositionTest {
    @Test
    fun savingWithdrawalMakesExporterPendingAndRemountRebindsIt() = runTest {
        var opened = 0
        var closed = 0
        lateinit var saver: ConversationImageSaver
        lateinit var exporter: ConversationExporter
        val runtime = KcodePluginRuntime.create(config(ConversationImageSaverFactory {
            opened++
            ConversationImageSaverResource(EmptySaver()) { closed++ }
        }, { saver = it }, { exporter = it }).copy(profile = KcodePluginProfile(disabled = setOf(ProviderId))))
        try {
            assertEquals(0, opened)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.export.conversation" }.state)
            runtime.pluginManager.setEnabled(ProviderId, true)
            val oldSaver = saver
            val oldExporter = exporter
            assertEquals(ImageSaveResult.Unsupported, saver.save(image, "test.png"))
            runtime.pluginManager.setEnabled(ProviderId, false)
            assertEquals(1, closed)
            assertFailsWith<IllegalStateException> { oldSaver.share(image, "test.png") }
            runtime.pluginManager.setEnabled(ProviderId, true)
            assertEquals(2, opened)
            assertNotSame(oldSaver, saver)
            assertNotSame(oldExporter, exporter)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.export.conversation" }.state)
        } finally { runtime.close() }
        assertEquals(opened, closed)
    }

    @Test
    fun cancelledInitializationReleasesAllocatedSaver() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        val creating = backgroundScope.async {
            KcodePluginRuntime.create(config(ConversationImageSaverFactory {
                entered.complete(Unit)
                release.await()
                ConversationImageSaverResource(EmptySaver()) { closed = true }
            }))
        }
        entered.await()
        creating.cancel()
        release.complete(Unit)
        creating.join()
        assertTrue(creating.isCancelled)
        assertTrue(closed)
    }

    @Test
    fun withdrawalWaitsForSavingCleanupBeforeRelease() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        lateinit var current: ConversationImageSaver
        val saver = object : EmptySaver() {
            override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val runtime = KcodePluginRuntime.create(config(ConversationImageSaverFactory {
            ConversationImageSaverResource(saver) { closed = true }
        }, { current = it }))
        try {
            val saving = backgroundScope.async { current.save(image, "fixture.png") }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled(ProviderId, false) }
            cleaning.await()
            assertFalse(closed)
            assertFalse(disabling.isCompleted)
            release.complete(Unit)
            disabling.await()
            saving.join()
            assertTrue(saving.isCancelled)
            assertTrue(closed)
        } finally { release.complete(Unit); runtime.close() }
    }

    private fun config(
        factory: ConversationImageSaverFactory,
        bindSaver: ((ConversationImageSaver) -> Unit)? = null,
        bindExporter: ((ConversationExporter) -> Unit)? = null,
    ) = KcodePluginRuntimeConfig(
        interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
        conversationImageSaverFactory = factory,
        featurePlugins = listOfNotNull(
            bindSaver?.let { bind -> kcodePlugin(descriptor("test.capture-saving"), plugin<Unit>(
                name = "capture-saving", inject = dependencies(KcodeConversationImageSaving.Key),
            ) { ctx, _ -> bind(ctx.require(KcodeConversationImageSaving.Key).saver) }, Unit) },
            bindExporter?.let { bind -> kcodePlugin(descriptor("test.capture-export"), plugin<Unit>(
                name = "capture-export", inject = dependencies(KcodeConversationExport.Key),
            ) { ctx, _ -> bind(ctx.require(KcodeConversationExport.Key).exporter) }, Unit) },
        ),
    )

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private open class EmptySaver : ConversationImageSaver {
        override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult = ImageSaveResult.Unsupported
        override suspend fun share(image: ImageBitmap, fileName: String): ImageSaveResult = ImageSaveResult.Unsupported
    }

    private val image = object : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace = ColorSpaces.Srgb
        override val hasAlpha = true
        override val config = ImageBitmapConfig.Argb8888
        override fun prepareToDraw() = error("Saving fixture must not draw")
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) =
            error("Saving fixture must not inspect pixels")
    }

    private companion object { const val ProviderId = "provider.export.image-saving" }
}
