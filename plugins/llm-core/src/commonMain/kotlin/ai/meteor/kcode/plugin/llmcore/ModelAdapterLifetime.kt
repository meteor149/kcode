package ai.meteor.kcode.plugin.llmcore

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.PluginCleanupException

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.meteor.kcode.AgentModelRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Allocation is bounded; returned clients and every collected stream belong to this adapter. */
internal class ModelAdapterLifetime(id: String) {
    private val name = "model adapter '$id'"
    private val owner = PluginOperationOwner(name)
    private val closing = MutableStateFlow(false)
    private val completion = CompletableDeferred<Unit>()
    private val clients = MutableStateFlow<Set<OwnedClient>>(emptySet())
    private val cleanupFailures = MutableStateFlow<List<Throwable>>(emptyList())
    val isOpen: Boolean get() = !closing.value

    suspend fun acquire(create: suspend () -> AgentModelRuntime): AgentModelRuntime = owner.acquire(
        acquire = {
            check(isOpen) { "$name is disposed" }
            val runtime = create()
            val client = OwnedClient(runtime.client)
            clients.update { it + client }
            AgentModelRuntime(client, runtime.model)
        },
        discard = { it.client.close() },
    )

    suspend fun requireCanClose() = owner.requireCanClose()

    suspend fun close() {
        requireCanClose()
        withContext(NonCancellable) {
            if (closing.compareAndSet(false, true)) {
                try {
                    val ownerFailure = runCatching { owner.close() }.exceptionOrNull()
                    clients.value.forEach { client ->
                        try {
                            client.close()
                        } catch (_: Throwable) {
                            // close records errors, including failed acquisitions discarded during teardown.
                        }
                    }
                    val ownerFailures = when (ownerFailure) {
                        is PluginCleanupException -> ownerFailure.failures
                        null -> emptyList()
                        else -> listOf(ownerFailure)
                    }
                    val failures = (cleanupFailures.value + ownerFailures).distinct()
                    if (failures.isNotEmpty()) throw PluginCleanupException(name, failures)
                    completion.complete(Unit)
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                    throw error
                }
            } else {
                completion.await()
            }
            Unit
        }
    }

    private inner class OwnedClient(private val delegate: LLMClient) : LLMClient() {
        private val closed = MutableStateFlow(false)

        private fun requireOpen() {
            check(isOpen && !closed.value) { "$name client is disposed" }
            owner.requireOpen()
        }

        private suspend fun <T> call(block: suspend () -> T): T = owner.run {
            requireOpen()
            block()
        }

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
            call { delegate.execute(prompt, model, tools) }

        override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
            call { delegate.executeMultipleChoices(prompt, model, tools) }

        override fun executeStreaming(prompt: Prompt, model: LLModel) = executeStreaming(prompt, model, emptyList())

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) = channelFlow {
            call { delegate.executeStreaming(prompt, model, tools).collect { send(it) } }
        }.buffer(0).also { requireOpen() }

        override suspend fun moderate(prompt: Prompt, model: LLModel) = call { delegate.moderate(prompt, model) }
        override suspend fun models() = call { delegate.models() }
        override suspend fun embed(text: String, model: LLModel) = call { delegate.embed(text, model) }
        override suspend fun embed(inputs: List<String>, model: LLModel) = call { delegate.embed(inputs, model) }

        private fun <T> metadata(block: () -> T): T {
            requireOpen()
            return block()
        }

        override fun llmProvider() = metadata { delegate.llmProvider() }
        override val clientName get() = metadata { delegate.clientName }
        override fun getStandardJsonSchemaGenerator() = metadata { delegate.getStandardJsonSchemaGenerator() }
        override fun getBasicJsonSchemaGenerator() = metadata { delegate.getBasicJsonSchemaGenerator() }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close()
                } catch (error: Throwable) {
                    cleanupFailures.update { it + error }
                    throw error
                } finally {
                    clients.update { it - this }
                }
            }
        }
    }
}
