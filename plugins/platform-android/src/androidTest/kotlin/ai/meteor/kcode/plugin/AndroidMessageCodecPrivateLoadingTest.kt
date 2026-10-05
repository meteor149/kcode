package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ChatMessageCodec
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ToolUseInfo
import ai.meteor.kcode.model.ToolUseStatus
import ai.meteor.kcode.plugin.messagecodec.MessageCodecProviderPlugin
import ai.meteor.kcode.plugin.api.KcodeMessageCodec
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import org.cordis.dependencies
import org.cordis.plugin
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AndroidMessageCodecPrivateLoadingTest {
    @Test(timeout = 60000)
    fun actualPackageCodecWithdrawalSuspendsReadersAndWritersAcrossRestart(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "codec-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "codec.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var codec: ChatMessageCodec
        val capture = kcodePlugin(
            PluginDescriptor("test.codec", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-message-codec", inject = dependencies(KcodeMessageCodec.Key)) { ctx, _ ->
                codec = ctx.require(KcodeMessageCodec.Key).codec
            },
            Unit,
        )
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        )
        var runtime = KcodePluginRuntime.create(configuration())
        val message = ChatMessage(
            id = 1L,
            role = MessageRole.Assistant,
            content = "private format",
            toolUses = listOf(ToolUseInfo("tool", "shell", "{}", status = ToolUseStatus.Succeeded)),
        )
        try {
            runtime.pluginManager.replace(DynamicPluginSpec(
                id = "provider.message-codec.envelope",
                version = "private-codec",
                artifactPath = artifact.path,
                sha256 = packageFileSha256(artifact),
                entryClass = MessageCodecProviderPlugin::class.java.name,
                packageName = instrumentation.context.packageName,
            ))
            val original = codec
            assertNotSame(ChatMessageCodec::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(MessageCodecProviderPlugin::class.java.classLoader, original.javaClass.classLoader)
            assertFailsWith<ClassNotFoundException> {
                Class.forName("ai.meteor.kcode.model.ChatMessagePersistenceKt", false, original.javaClass.classLoader)
            }
            val encoded = original.encode(message)
            assertEquals(message.content, original.decode(encoded).text)
            assertEquals(message.toolUses, original.decode(encoded).toolUses)
            runtime.pluginManager.setEnabled("provider.message-codec.envelope", false)
            for (id in listOf("provider.sessions.history", "provider.conversation-execution.history")) {
                assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == id }.state, id)
            }
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.ui.compose" }.state)
            assertFailsWith<IllegalStateException> { original.encode(message) }
            assertFailsWith<IllegalStateException> { original.decode(encoded) }
            runtime.close()
            runtime = KcodePluginRuntime.create(configuration())
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.sessions.history" }.state)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.ui.compose" }.state)
            runtime.pluginManager.setEnabled("provider.message-codec.envelope", true)
            assertNotSame(original, codec)
            assertEquals(message.toolUses, codec.decode(encoded).toolUses)
            for (id in listOf("provider.sessions.history", "provider.conversation-execution.history", "provider.ui.compose")) {
                assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == id }.state, id)
            }
            val restored = codec
            assertNotSame(ChatMessageCodec::class.java.classLoader, restored.javaClass.classLoader)
            runtime.pluginManager.uninstall("provider.message-codec.envelope")
            assertFailsWith<IllegalStateException> { restored.decode(encoded) }
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.sessions.history" }.state)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.ui.compose" }.state)
            runtime.pluginManager.setEnabled("provider.message-codec.envelope", true)
            assertEquals(message.toolUses, codec.decode(encoded).toolUses)
            assertNotSame(restored, codec)
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
