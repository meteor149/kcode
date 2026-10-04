package ai.meteor.kcode.plugin

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class OwnedAgentChatServiceTest {
    @Test
    fun withdrawalJoinsReplyAndStreamingCleanupAndRejectsTheOldService() = runTest {
        for (streaming in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val owner = PluginOperationOwner("test agent")
            val delegate = object : ChatService {
                override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String): String {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                    }
                }
            }
            val service = OwnedAgentChatService(delegate, owner)
            val config = ModelConfiguration(ModelProvider.Ollama, "fixture", "", temperature = 0.6)
            val operation = backgroundScope.async {
                if (streaming) service.replyStreaming(config, emptyList(), "prompt", onDelta = {})
                else service.reply(config, emptyList(), "prompt")
            }
            try {
                entered.await()
                val closing = async { owner.close() }
                cleaning.await()
                assertFalse(closing.isCompleted)
                assertFailsWith<IllegalStateException> { service.reply(config, emptyList(), "late") }
                assertFailsWith<IllegalStateException> { service.replyStreaming(config, emptyList(), "late", onDelta = {}) }
                release.complete(Unit)
                closing.await()
                assertTrue(operation.isCompleted)
            } finally { release.complete(Unit); owner.close() }
        }
    }
}
