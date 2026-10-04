package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.chat.ConversationCommand
import ai.meteor.kcode.chat.ConversationCommandContribution
import ai.meteor.kcode.chat.ConversationCommandOperations
import ai.meteor.kcode.chat.ConversationCommandRequest
import ai.meteor.kcode.chat.GoalSession
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationCommandsTest {
    @Test
    fun disposalCancelsAndJoinsCommandAndRevokesResolvedHandle() = runBlocking {
        val commands = KcodeConversationCommands(Context())
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val contribution = ConversationCommandContribution("fixture") { input ->
            if (input != "/fixture") null else object : ConversationCommand {
                override val allowedDuringGeneration = true
                override suspend fun execute(request: ConversationCommandRequest) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
            }
        }
        val registration = commands.register(contribution)
        assertTrue(commands.committedSnapshot.contributions.isEmpty())
        commands.commitSnapshot()
        val resolved = checkNotNull(commands.committedSnapshot.resolve("/fixture"))
        val operation = async { resolved.execute(request) }
        entered.await()
        registration.dispose()
        operation.join()
        assertTrue(stopped.isCompleted)
        assertTrue(operation.isCancelled)
        assertFalse(commands.committedSnapshot.allowedDuringGeneration("/fixture"))
        assertFailsWith<IllegalStateException> { resolved.execute(request) }
        commands.commitSnapshot()
        assertTrue(commands.committedSnapshot.contributions.isEmpty())
    }

    @Test
    fun duplicateContributionCannotChangeOrderedSnapshot() = runBlocking {
        val commands = KcodeConversationCommands(Context())
        val first = commands.register(ConversationCommandContribution("later", 20) { null })
        val second = commands.register(ConversationCommandContribution("first", 10) { null })
        try {
            val before = commands.commitSnapshot()
            assertEquals(listOf("first", "later"), before.contributions.map { it.id })
            assertFailsWith<IllegalArgumentException> {
                commands.register(ConversationCommandContribution("first", 0) { null })
            }
            assertEquals(before, commands.snapshot())
            assertEquals(before, commands.committedSnapshot)
        } finally { second.dispose(); first.dispose() }
    }

    private val request = ConversationCommandRequest("/fixture", AppLanguage.English, null,
        { error("Fixture must not create a conversation") }, object : ConversationCommandOperations {
            override fun nextMessageId(target: ConversationState): Long = error("unused")
            override suspend fun appendFeedback(target: ConversationState, user: ChatMessage, content: String, isError: Boolean) = error("unused")
            override suspend fun startResponse(target: ConversationState, user: ChatMessage, prompt: String, goalSession: GoalSession?) = error("unused")
        })
}
