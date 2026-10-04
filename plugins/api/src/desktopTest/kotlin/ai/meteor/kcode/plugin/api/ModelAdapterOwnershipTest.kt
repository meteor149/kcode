package ai.meteor.kcode.plugin.api

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.streaming.StreamFrame
import ai.meteor.kcode.AgentModelRuntime
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelAdapterOwnershipTest {
    @Test
    fun revokeCancelsOrdinaryRequestAndWaitsBeforeClosingClient(): Unit = runTest {
        val client = WaitingClient()
        val registry = KcodeLlm(Context())
        val registration = registry.register(adapter { client })
        val runtime = registry.resolve(configuration).create(configuration, factory)
        val request = launch { runtime.client.execute(prompt, model, emptyList()) }
        client.entered.await()
        val closing = async { registration.dispose() }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(0, client.closed)
        client.cleanup.complete(Unit)
        closing.await()
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, client.closed)
        assertFailsWith<IllegalStateException> { runtime.client.execute(prompt, model, emptyList()) }
    }

    @Test
    fun revokeCancelsStreamCollectorAndWaitsForItsCleanup(): Unit = runTest {
        val client = WaitingClient()
        val registry = KcodeLlm(Context())
        val registration = registry.register(adapter { client })
        val runtime = registry.resolve(configuration).create(configuration, factory)
        val stream = runtime.client.executeStreaming(prompt, model, emptyList())
        val collector = launch { stream.collect { error("Unexpected frame") } }
        client.entered.await()
        val closing = async { registration.dispose() }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(0, client.closed)
        client.cleanup.complete(Unit)
        closing.await()
        collector.join()
        assertTrue(collector.isCancelled)
        assertFailsWith<IllegalStateException> { stream.collect { } }
        assertEquals(1, client.closed)
    }

    @Test
    fun creationDuringRevocationDiscardsClientBeforeDisposeReturns(): Unit = runTest {
        val entered = CompletableDeferred<Unit>()
        val allocated = CompletableDeferred<Unit>()
        val client = WaitingClient()
        val registry = KcodeLlm(Context())
        val registration = registry.register(adapter {
            entered.complete(Unit)
            allocated.await()
            client
        })
        val resolved = registry.resolve(configuration)
        val creation = launch { resolved.create(configuration, factory) }
        entered.await()
        val closing = async { registration.dispose() }
        runCurrent()
        assertFalse(closing.isCompleted)
        allocated.complete(Unit)
        closing.await()
        creation.join()
        assertTrue(creation.isCancelled)
        assertEquals(1, client.closed)
        assertFailsWith<IllegalStateException> { resolved.create(configuration, factory) }
    }

    @Test
    fun consumerCloseReleasesClientOnceAndRevokesEveryEntry(): Unit = runTest {
        val client = WaitingClient()
        val registry = KcodeLlm(Context())
        val registration = registry.register(adapter { client })
        val runtime = registry.resolve(configuration).create(configuration, factory)
        val stream = runtime.client.executeStreaming(prompt, model)
        runtime.client.close()
        runtime.client.close()
        assertFailsWith<IllegalStateException> { runtime.client.models() }
        assertFailsWith<IllegalStateException> { runtime.client.moderate(prompt, model) }
        assertFailsWith<IllegalStateException> { runtime.client.embed("fixture", model) }
        assertFailsWith<IllegalStateException> { runtime.client.embed(listOf("fixture"), model) }
        assertFailsWith<IllegalStateException> { runtime.client.executeMultipleChoices(prompt, model, emptyList()) }
        assertFailsWith<IllegalStateException> { stream.collect { } }
        registration.dispose()
        assertEquals(1, client.closed)
    }

    @Test
    fun failingClientCloseStillClosesOthersAndRepeatedDisposeReportsFailure(): Unit = runTest {
        val first = WaitingClient(IllegalArgumentException("fixture close"))
        val second = WaitingClient()
        var allocations = 0
        val registry = KcodeLlm(Context())
        val registration = registry.register(adapter { if (allocations++ == 0) first else second })
        val resolved = registry.resolve(configuration)
        resolved.create(configuration, factory)
        resolved.create(configuration, factory)
        assertFailsWith<PluginCleanupException> { registration.dispose() }.also { error ->
            assertTrue(error.failures.any { it is IllegalArgumentException })
        }
        assertEquals(1, first.closed)
        assertEquals(1, second.closed)
        assertFailsWith<PluginCleanupException> { registration.dispose() }.also { error ->
            assertTrue(error.failures.any { it is IllegalArgumentException })
        }
        assertTrue(registry.adapterIds().isEmpty())
    }

    @Test
    fun streamFramesKeepOrderAndDoNotCloseTheClientUntilReleased(): Unit = runTest {
        val client = WaitingClient()
        val frames = listOf(StreamFrame.TextDelta("first"), StreamFrame.TextDelta("second"))
        client.frames = frames
        val registry = KcodeLlm(Context())
        val registration = registry.register(adapter { client })
        val runtime = registry.resolve(configuration).create(configuration, factory)
        assertEquals(frames, runtime.client.executeStreaming(prompt, model, emptyList()).toList())
        assertEquals(0, client.closed)
        runtime.client.close()
        registration.dispose()
        assertEquals(1, client.closed)
    }

    @Test
    fun reentrantDisposeFailsWithoutRemovingTheAdapter(): Unit = runTest {
        val client = WaitingClient()
        val registry = KcodeLlm(Context())
        val registration = registry.register(adapter { client })
        client.beforeWait = { registration.dispose() }
        val runtime = registry.resolve(configuration).create(configuration, factory)
        assertFailsWith<IllegalStateException> { runtime.client.execute(prompt, model, emptyList()) }
        assertEquals(listOf("fixture"), registry.adapterIds())
        assertEquals(0, client.closed)
        registration.dispose()
        assertEquals(1, client.closed)
    }

    @Test
    fun discardedAllocationCloseFailureIsReportedByDispose(): Unit = runTest {
        val entered = CompletableDeferred<Unit>()
        val allocated = CompletableDeferred<Unit>()
        val client = WaitingClient(IllegalArgumentException("discard failed"))
        val registry = KcodeLlm(Context())
        val registration = registry.register(adapter {
            entered.complete(Unit)
            allocated.await()
            client
        })
        val resolved = registry.resolve(configuration)
        val creation = launch { runCatching { resolved.create(configuration, factory) } }
        entered.await()
        val closing = async { runCatching { registration.dispose() } }
        runCurrent()
        assertFalse(closing.isCompleted)
        allocated.complete(Unit)
        assertFailsWith<PluginCleanupException> { closing.await().getOrThrow() }.also { error ->
            assertTrue(error.failures.any { it is IllegalArgumentException })
        }
        creation.join()
        assertEquals(1, client.closed)
    }

    private fun adapter(create: suspend () -> LLMClient) = ModelAdapter(
        id = "fixture",
        supports = { true },
        create = { _, _ -> AgentModelRuntime(create(), model) },
    )

    private class WaitingClient(private val closeFailure: Throwable? = null) : LLMClient() {
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        var closed = 0
        var beforeWait: (suspend () -> Unit)? = null
        var frames: List<StreamFrame>? = null
        private suspend fun waitUntilCanceled(): Nothing {
            beforeWait?.invoke()
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Nothing =
            waitUntilCanceled()
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            flow {
                val available = frames
                if (available == null) waitUntilCanceled() else available.forEach { emit(it) }
            }
        override suspend fun moderate(prompt: Prompt, model: LLModel): Nothing = error("Unexpected moderation")
        override fun llmProvider() = LLMProvider.OpenAI
        override fun close() {
            closed++
            closeFailure?.let { throw it }
        }
    }

    private companion object {
        val configuration = ModelConfiguration(ModelProvider.OpenAI, "fixture", "", temperature = 0.6)
        val model = LLModel(LLMProvider.OpenAI, "fixture", emptyList())
        val prompt = Prompt.build("fixture") { user("fixture") }
        val factory = KtorKoogHttpClient.Factory()
    }
}
