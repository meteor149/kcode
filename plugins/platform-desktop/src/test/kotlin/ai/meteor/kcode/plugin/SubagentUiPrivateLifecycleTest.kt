package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.localization.LocalizationContext
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.SubAgentInfo
import ai.meteor.kcode.model.SubAgentRunStatus
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.plugin.api.KcodeSchedules
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationContent
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPosition
import ai.meteor.kcode.plugin.ui.api.ConversationPageContext
import ai.meteor.kcode.plugin.ui.api.KcodeUiSlots
import ai.meteor.kcode.session.HistoryConversationState
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubagentUiPrivateLifecycleTest {
    @Test
    fun privateAggregateOwnsOptionalPresentationAndWithdrawsRetainedPresenter(): Unit = runBlocking {
        val directory = Files.createTempDirectory("private-subagent-ui").toFile()
        val artifact = File(directory, "subagents.jar")
        File(SubagentFeaturePlugin::class.java.protectionDomain.codeSource.location.toURI()).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var services: Context
        val capture = kcodePlugin(PluginDescriptor("test.subagent-ui", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-subagent-ui", inject = dependencies(KcodeUiSlots.Key, KcodeSchedules.Key, KcodeGeneration.Key),
        ) { ctx, _ -> services = ctx }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        try {
            val deployment = DynamicPluginSpec(
                id = "feature.subagents", version = "private-ui", artifactPath = artifact.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
                entryClass = SubagentFeaturePlugin::class.java.name,
            )
            runtime.pluginManager.replace(deployment)
            fun slots() = services.require(KcodeUiSlots.Key)
            var decoration by mutableStateOf(slots().snapshot().conversationDecorations.single { it.id == "subagents" })
            assertNotSame(SubagentFeaturePlugin::class.java.classLoader, uiImplementation(decoration.presenter).javaClass.classLoader)
            assertSame(ConversationDecorationPosition::class.java,
                Class.forName(ConversationDecorationPosition::class.java.name, false, uiImplementation(decoration.presenter).javaClass.classLoader))
            val target = HistoryConversationState(81, "subagents")
            val agent = SubAgentInfo("/root/worker", "/root", "worker", "prompt")
            target.messages += ChatMessage(1, MessageRole.Assistant, "", subAgents = listOf(agent))
            target.messages += ChatMessage(2, MessageRole.Assistant, "", subAgents = listOf(agent.copy(status = SubAgentRunStatus.Completed)))
            val page = ConversationPageContext(target, true, null, runtime.chatService,
                services.require(KcodeGeneration.Key).runner, services.require(KcodeSchedules.Key).coordinator,
                ChatFailureMessages("setup", "connection"), {})
            val skin = slots().snapshot()
            var content: ConversationDecorationContent? = null
            withContext(Dispatchers.Main.immediate) {
                val scene = ImageComposeScene(width = 400, height = 400, coroutineContext = coroutineContext) {
                    val prepared = decoration.presenter.Present(page).singleOrNull()
                    SideEffect { content = prepared }
                    requireNotNull(skin.theme).Render {
                        LocalizationContext(skin.localization, "en") {
                            prepared?.renderer?.Render(Modifier.fillMaxWidth())
                        }
                    }
                }
                var frame = 0L
                suspend fun render() {
                    repeat(4) { scene.render(frame++ * 100_000_000L).close(); delay(10) }
                }
                try {
                    render()
                    assertNull(content, "A later completed update must hide an older running state")
                    target.messages.removeAt(1)
                    render()
                    assertEquals(ConversationDecorationPosition.AboveComposer, content?.position)
                    val original = decoration
                    runtime.pluginManager.setEnabled("core.ui-slots", false)
                    render()
                    assertNull(content, "Retained presenters withdraw when the optional UI service is gone")
                    assertTrue("core/subagent" in runtime.diagnostics().toolContributions)
                    runtime.pluginManager.setEnabled("core.ui-slots", true)
                    decoration = slots().snapshot().conversationDecorations.single { it.id == "subagents" }
                    assertNotSame(original.presenter, decoration.presenter)
                    render()
                    assertEquals(ConversationDecorationPosition.AboveComposer, content?.position)
                    runtime.pluginManager.setEnabled("feature.subagents", false)
                    render()
                    assertNull(content)
                    assertTrue(slots().snapshot().conversationDecorations.none { it.id == "subagents" })
                    assertTrue(slots().snapshot().chat != null)
                    assertTrue("core/subagent" !in runtime.diagnostics().toolContributions)
                    runtime.pluginManager.setEnabled("feature.subagents", true)
                    decoration = slots().snapshot().conversationDecorations.single { it.id == "subagents" }
                    render()
                    assertEquals(ConversationDecorationPosition.AboveComposer, content?.position)
                } finally { scene.close() }
            }
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
