package ai.meteor.kcode.plugin.agentloop

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.AgentModelRuntime
import ai.meteor.kcode.SubagentCoordinator
import ai.meteor.kcode.SubagentCoordinatorFactory
import ai.meteor.kcode.chat.ToolUseEvent
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class KoogClientLifecycleTest {
    @Test
    fun toolInitializationFailureClosesClientAndPreservesPrimaryFailure(): Unit = runTest {
        val cleanupFailure = IllegalStateException("close failed")
        val client = RecordingClient(cleanupFailure)
        val failure = IllegalArgumentException("tool setup failed")
        val service = service(client) { throw failure }
        val observed = assertFailsWith<IllegalArgumentException> {
            service.reply(configuration, emptyList(), "fixture")
        }
        val original = generateSequence<Throwable>(observed) { it.cause }.last()
        assertSame(failure, original)
        assertEquals(1, client.closed)
        assertEquals(listOf(cleanupFailure), original.suppressedExceptions)
    }

    @Test
    fun canceledToolInitializationClosesClientBeforeTurnReturns(): Unit = runTest {
        val entered = CompletableDeferred<Unit>()
        val client = RecordingClient()
        val service = service(client) {
            entered.complete(Unit)
            awaitCancellation()
        }
        val turn = launch { service.reply(configuration, emptyList(), "fixture") }
        entered.await()
        turn.cancelAndJoin()
        assertEquals(1, client.closed)
    }

    @Test
    fun overlayCapabilityIsQueriedEachTurnAndFinishedOnSetupFailure() = runTest {
        var started = 0
        var finished = 0
        var resolved = 0
        val controller = object : AgentConversationOverlayController {
            override suspend fun setHostForeground(isForeground: Boolean) = Unit
            override suspend fun startTurn(initialMessages: List<ChatMessage>): AgentConversationOverlayTurn {
                started++
                return object : AgentConversationOverlayTurn {
                    override suspend fun update(messages: List<ChatMessage>) = Unit
                    override suspend fun finish() { finished++ }
                }
            }
        }
        val service = service(RecordingClient(), overlayProvider = {
            resolved++
            if (resolved == 2) null else controller
        }) { throw IllegalArgumentException("tool setup failed") }
        repeat(3) {
            assertFailsWith<IllegalArgumentException> { service.reply(configuration, emptyList(), "fixture") }
        }
        assertEquals(3, resolved)
        assertEquals(2, started)
        assertEquals(2, finished)
    }

    @Test
    fun overlayInitializationFailureStillShutsDownCoordinator() = runTest {
        var closed = 0
        val service = service(
            RecordingClient(),
            overlayProvider = { throw IllegalArgumentException("overlay initialization failed") },
            coordinatorFactory = SubagentCoordinatorFactory { _, _, _, _ -> IdleCoordinator { closed++ } },
        ) { ToolRegistry.EMPTY }
        assertFailsWith<IllegalArgumentException> { service.reply(configuration, emptyList(), "fixture") }
        assertEquals(1, closed)
    }

    private fun service(
        client: RecordingClient,
        overlayProvider: suspend () -> AgentConversationOverlayController? = { null },
        coordinatorFactory: SubagentCoordinatorFactory = SubagentCoordinatorFactory { _, _, _, _ -> IdleCoordinator() },
        tools: suspend () -> ToolRegistry,
    ) = KoogChatService(
        additionalToolsProvider = { tools() },
        modelRuntimeProvider = { _, _ -> AgentModelRuntime(client, LLModel(LLMProvider.OpenAI, "fixture", emptyList())) },
        systemPromptProvider = { _, _ -> "fixture" },
        continuationProvider = { null },
        subagentCoordinatorFactory = coordinatorFactory,
        conversationOverlayProvider = overlayProvider,
        toolPermissionModeProvider = { ToolPermissionMode.Bypass },
        toolCallApprover = ToolCallApprover { true },
    )

    private class RecordingClient(private val cleanupFailure: Throwable? = null) : LLMClient() {
        var closed = 0
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Nothing =
            error("Unexpected model execution")
        override suspend fun moderate(prompt: Prompt, model: LLModel): Nothing =
            error("Unexpected moderation")
        override fun llmProvider() = LLMProvider.OpenAI
        override fun close() {
            closed++
            cleanupFailure?.let { throw it }
        }
    }

    private class IdleCoordinator(val onClose: () -> Unit = {}) : SubagentCoordinator {
        override suspend fun spawn(callerPath: String, taskName: String, message: String, forkTurns: String?): String = error("Unexpected spawn")
        override suspend fun sendMessage(callerPath: String, target: String, message: String): String = error("Unexpected message")
        override suspend fun followupTask(callerPath: String, target: String, message: String): String = error("Unexpected followup")
        override suspend fun interrupt(callerPath: String, target: String): String = error("Unexpected interrupt")
        override suspend fun list(callerPath: String, pathPrefix: String?): String = ""
        override suspend fun waitForUpdate(callerPath: String): String = ""
        override suspend fun drainMailbox(agentPath: String): String = ""
        override suspend fun continuationAfterRootResponse(): String? = null
        override suspend fun onToolUse(agentPath: String, event: ToolUseEvent) = Unit
        override suspend fun shutdown() = onClose()
    }

    private companion object {
        val configuration = ModelConfiguration(ModelProvider.OpenAI, "fixture", "", temperature = 0.6)
    }
}
