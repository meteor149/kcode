package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.ui.api.MarkdownContent
import ai.meteor.kcode.plugin.markdown.MarkdownFeaturePlugin
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.plugin.ui.api.KcodeMarkdown
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import org.cordis.dependencies
import org.cordis.plugin
import java.nio.file.Files
import kotlin.test.Test

class MarkdownPrivateLoadingTest {
    @Test
    fun actualPackageOwnsFormattingAndWithdrawsUiExportAndNotifications(): Unit = runBlocking {
        val directory = Files.createTempDirectory("codec-private").toFile()
        val artifact = File(directory, "codec.jar")
        File(MarkdownFeaturePlugin::class.java.protectionDomain.codeSource.location.toURI()).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var codec: MarkdownContent
        lateinit var slots: KcodeUiSlots
        lateinit var services: org.cordis.Context
        val capture = kcodePlugin(
            PluginDescriptor("test.codec", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-message-codec", inject = dependencies(KcodeMarkdown.Key, KcodeUiSlots.Key)) { ctx, _ ->
                codec = ctx.require(KcodeMarkdown.Key).content
                slots = ctx.require(KcodeUiSlots.Key)
            },
            Unit,
        )
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                services = ctx
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        )
        var runtime = KcodePluginRuntime.create(configuration())
        try {
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "feature.markdown",
                version = "private-codec",
                artifactPath = artifact.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
                entryClass = MarkdownFeaturePlugin::class.java.name,

            ))
            val original = codec
            assertNotSame(MarkdownContent::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(MarkdownFeaturePlugin::class.java.classLoader, original.javaClass.classLoader)
            assertFailsWith<ClassNotFoundException> {
                Class.forName("ai.meteor.kcode.ui.component.MarkdownTextKt", false, original.javaClass.classLoader)
            }
            assertSame(original, slots.snapshot().markdown)
            assertEquals("A bold result", original.plainText("A **bold** result"))
            assertTrue(original.inline("**bold**", original.palette.accent).spanStyles.isNotEmpty())
            assertEquals(1, original.blocks("# heading").size)
            runtime.pluginManager.setEnabled("feature.markdown", false)
            for (id in listOf("feature.conversation-export", "feature.schedule")) {
                assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == id }.state, id)
            }
            assertNull(slots.snapshot().markdown)
            assertNull(services[ai.meteor.kcode.plugin.api.KcodeConversationExport.Key])
            assertFailsWith<IllegalStateException> { original.plainText("stale") }
            assertFailsWith<IllegalStateException> { original.blocks("stale") }
            runtime.close()
            runtime = KcodePluginRuntime.create(configuration())
            assertNull(slots.snapshot().markdown)
            assertNull(services[ai.meteor.kcode.plugin.api.KcodeConversationExport.Key])
            runtime.pluginManager.setEnabled("feature.markdown", true)
            assertNotSame(original, codec)
            assertEquals("A bold result", codec.plainText("A **bold** result"))
            for (id in listOf("feature.conversation-export", "feature.schedule")) {
                assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == id }.state, id)
            }
            val restored = codec
            assertNotSame(MarkdownContent::class.java.classLoader, restored.javaClass.classLoader)
            runtime.pluginManager.uninstall("feature.markdown")
            assertFailsWith<IllegalStateException> { restored.plainText("stale") }
            assertNull(slots.snapshot().markdown)
            assertNull(services[ai.meteor.kcode.plugin.api.KcodeConversationExport.Key])
            runtime.pluginManager.setEnabled("feature.markdown", true)
            assertEquals("A bold result", codec.plainText("A **bold** result"))
            assertNotSame(restored, codec)
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
