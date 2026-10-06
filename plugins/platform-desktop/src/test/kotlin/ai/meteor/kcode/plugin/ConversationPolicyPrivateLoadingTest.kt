package ai.meteor.kcode.plugin

import ai.meteor.kcode.KcodeAgentRuntime
import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ConversationResponseRequest
import ai.meteor.kcode.chat.UnavailableGoalSessions
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.KcodeGeneration
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.ConversationSessionFactory
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.KcodeConversationExecution
import ai.meteor.kcode.plugin.api.KcodeSessions
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.cordis.dependencies
import org.cordis.plugin
import java.nio.file.Files
import kotlin.test.Test

class ConversationPolicyPrivateLoadingTest {
    @Test
    fun actualPackagesOwnConversationPoliciesAndExecutors(): Unit = runBlocking {
        val directory = Files.createTempDirectory("private-policy").toFile()
        val artifacts = mutableMapOf<File, File>()
        fun artifactFor(entry: Class<*>): File {
            val source = File(entry.protectionDomain.codeSource.location.toURI())
            return artifacts.getOrPut(source) {
                File(directory, "module-${artifacts.size}.jar").also { source.copyTo(it); check(it.setReadOnly()) }
            }
        }
        fun digestFor(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        lateinit var factory: ConversationSessionFactory
        lateinit var execution: ConversationExecution
        lateinit var chat: ChatService
        lateinit var generation: ChatGenerationRunner
        val capture = kcodePlugin(
            PluginDescriptor("test.policy", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-private-policy", inject = dependencies(KcodeSessions.Key, KcodeConversationExecution.Key, KcodeAgents.Key, KcodeGeneration.Key)) { ctx, _ ->
                factory = ctx.require(KcodeSessions.Key).factory
                execution = ctx.require(KcodeConversationExecution.Key).executor
                chat = ctx.require(KcodeAgents.Key).chatService
                generation = ctx.require(KcodeGeneration.Key).runner
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        ))
        val preparing = CompletableDeferred<Unit>()
        val finishPreparation = CompletableDeferred<Unit>()
        val host = KcodeProfileHost(KcodeAgentRuntime(chatService = runtime.chatService, owner = runtime),
            "native", ProfileRuntimeFactory {
                preparing.complete(Unit)
                finishPreparation.await()
                throw IllegalArgumentException("Rejected preparation")
            })
        try {
            for ((id, entry) in listOf(
                "provider.sessions.history" to SessionHistoryProviderPlugin::class.java,
                "provider.conversation-execution.history" to ConversationExecutionProviderPlugin::class.java,
                "provider.agent-loop.koog" to KoogAgentLoopPlugin::class.java,
            )) {
                val artifact = artifactFor(entry)
                runtime.pluginManager.replace(DynamicPluginSpec(
                    id = id,
                    version = "private-policy",
                    artifactPath = artifact.path,
                    sha256 = digestFor(artifact),
                    entryClass = entry.name,

                ))
            }
            val session = factory.create(CoroutineScope(coroutineContext))
            try {
                session.load()
                assertTrue(session.isLoaded)
                assertNotSame(ConversationSessionFactory::class.java.classLoader, session.javaClass.classLoader)
                assertNotSame(SessionHistoryProviderPlugin::class.java.classLoader, session.javaClass.classLoader)
                val titleClass = Class.forName("ai.meteor.kcode.session.DefaultConversationTitleKt", false, session.javaClass.classLoader)
                assertSame(session.javaClass.classLoader, titleClass.classLoader)
                val conversation = session.ensureConversation("  private   title  ")
                assertEquals("private title", conversation.title)
                assertSame(session.javaClass.classLoader, conversation.javaClass.classLoader)
                assertNotSame(ai.meteor.kcode.ui.state.ConversationState::class.java.classLoader, conversation.javaClass.classLoader)
                assertEquals(1L, conversation.reserveMessageIds(2))
                conversation.messages += ChatMessage(50L, MessageRole.User, "restored")
                assertEquals(51L, conversation.reserveMessageIds())
                val background = factory.create(CoroutineScope(coroutineContext))
                try {
                    background.load()
                    val standalone = background.createPendingStandaloneConversation("private scheduled")
                    background.setPendingStandaloneResult(standalone.id, "private background result")
                    background.appendPendingStandaloneResultMessage(standalone.id)
                    background.revealStandaloneConversation(standalone.id)
                    val visible = session.floatingConversations.single { it.id == standalone.id }
                    assertEquals("private background result", visible.standaloneResult)
                    assertEquals("private background result", visible.messages.last().content)
                    assertSame(session.javaClass.classLoader, visible.javaClass.classLoader)
                } finally {
                    background.close()
                }
                assertNotSame(ConversationExecution::class.java.classLoader, execution.javaClass.classLoader)
                assertNotSame(ConversationExecutionProviderPlugin::class.java.classLoader, execution.javaClass.classLoader)
                assertNotSame(ChatService::class.java.classLoader, chat.javaClass.classLoader)
                assertNotSame(KoogAgentLoopPlugin::class.java.classLoader, chat.javaClass.classLoader)
                for (sdkType in listOf(ai.meteor.kcode.AgentLifecycle::class.java, ai.meteor.kcode.ToolExecutionLifecycle::class.java, ai.meteor.kcode.ApplicationContent::class.java)) {
                    assertSame(sdkType, Class.forName(sdkType.name, false, chat.javaClass.classLoader))
                }
                val contextClass = Class.forName("ai.meteor.kcode.plugin.agentloop.DefaultConversationContextKt", false, chat.javaClass.classLoader)
                assertSame(chat.javaClass.classLoader, contextClass.classLoader)
                val skillContext = contextClass.getDeclaredMethod("appendSelectedSkillFragments",
                    ai.meteor.kcode.skill.SkillTurnContext::class.java, String::class.java).invoke(null,
                    ai.meteor.kcode.skill.SkillTurnContext("catalog metadata", listOf("selected skill"), emptyList()),
                    "conversation",
                ) as String
                assertEquals("selected skill\n\nconversation", skillContext)
                val context = contextClass.getDeclaredMethod("buildContext", List::class.java, String::class.java).invoke(null,
                    listOf(ChatMessage(1L, MessageRole.User, "question"), ChatMessage(2L, MessageRole.Assistant, "failed", isError = true)),
                    "continue",
                ) as String
                assertTrue("User: question" in context)
                assertTrue("failed" !in context)
                assertTrue(context.endsWith("User: continue"))
                assertFailsWith<ClassNotFoundException> {
                    Class.forName("ai.meteor.kcode.model.ChatModelsKt", false, chat.javaClass.classLoader)
                }
                val committedTranscript = conversation.messages.toList()
                conversation.executionFailure = "previous"
                val switching = async { assertFailsWith<IllegalArgumentException> { host.switchTo("rejected") } }
                withTimeout(5_000) { preparing.await() }
                try {
                    val requestScope = CoroutineScope(coroutineContext)
                    val failureMessages = ChatFailureMessages("setup", "connection")
                    execution.sendMessage("setup", null, conversation, { error("Denied allocation") }, chat,
                        generation, UnavailableGoalSessions, requestScope, failureMessages, AppLanguage.English,
                        onUserMessageAdded = { _, _ -> error("Denied feedback") }, followBottom = {})
                    execution.sendMessage("new", null, null, { error("Denied new conversation") }, chat,
                        generation, UnavailableGoalSessions, requestScope, failureMessages, AppLanguage.English,
                        onUserMessageAdded = { _, _ -> error("Denied feedback") }, followBottom = {})
                    assertFailsWith<CancellationException> {
                        execution.startResponse(conversation, ConversationResponseRequest("denied", userMessage = "input"),
                            ModelConfiguration(ModelProvider.DeepSeek, "test", "", 0.4), chat, generation, failureMessages)
                    }
                    assertEquals(committedTranscript, conversation.messages.toList())
                    assertEquals("previous", conversation.executionFailure)
                    assertFalse(conversation.isGenerating)
                    assertEquals(0, generation.activeTasks.value)
                    assertEquals(52L, conversation.reserveMessageIds())
                } finally {
                    finishPreparation.complete(Unit)
                    switching.await()
                }
                assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
                val previous = factory
                runtime.pluginManager.setEnabled("provider.sessions.history", false)
                assertFailsWith<IllegalStateException> { session.ensureConversation("stale") }
                assertFailsWith<IllegalStateException> { previous.create(CoroutineScope(coroutineContext)) }
                runtime.pluginManager.setEnabled("provider.sessions.history", true)
                assertNotSame(previous, factory)
            } finally {
                session.close()
            }
        } finally {
            finishPreparation.complete(Unit)
            host.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
