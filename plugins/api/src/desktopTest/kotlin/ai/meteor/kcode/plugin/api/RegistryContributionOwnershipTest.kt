@file:OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)

package ai.meteor.kcode.plugin.api

import ai.koog.agents.core.tools.SimpleTool
import kotlinx.serialization.Serializable
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import ai.meteor.kcode.AgentContinuationContext
import ai.meteor.kcode.AgentToolContext
import ai.meteor.kcode.SubagentCoordinator
import ai.meteor.kcode.chat.ToolUseEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.Disposable
import org.cordis.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegistryContributionOwnershipTest {
    @Test
    fun promptRemovalCancelsAndWaitsForRendererCleanup(): Unit = runTest {
        val service = KcodeSystemPrompt(Context())
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val registration = service.register(PromptSection("fixture") { waitForRevoke(entered, cleanup) })
        val rendering = launch { service.render(promptRequest) }
        entered.await()
        val removal = async { registration.dispose() }
        runCurrent()
        assertFalse(removal.isCompleted)
        cleanup.complete(Unit)
        removal.await()
        rendering.join()
        assertTrue(rendering.isCancelled)
        assertEquals("", service.render(promptRequest))
    }

    @Test
    fun promptSnapshotCannotInvokeSectionRemovedWhilePreviousSectionRenders(): Unit = runTest {
        val service = KcodeSystemPrompt(Context())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        service.register(PromptSection("first", 0) { entered.complete(Unit); resume.await(); "first" })
        var invoked = false
        val old = service.register(PromptSection("second", 1) { invoked = true; "stale" })
        val rendering = async { runCatching { service.render(promptRequest) } }
        entered.await()
        old.dispose()
        service.register(PromptSection("second", 1) { "replacement" })
        old.dispose()
        resume.complete(Unit)
        assertFailsWith<IllegalStateException> { rendering.await().getOrThrow() }
        assertFalse(invoked)
        assertEquals("first\n\nreplacement", service.render(promptRequest))
    }

    @Test
    fun continuationRemovalCancelsAndWaitsForPolicyCleanup(): Unit = runTest {
        val service = KcodeContinuations(Context())
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val registration = service.register(ContinuationPolicy("fixture", 0) { waitForRevoke(entered, cleanup) })
        val evaluation = launch { service.next(continuationContext) }
        entered.await()
        val removal = async { registration.dispose() }
        runCurrent()
        assertFalse(removal.isCompleted)
        cleanup.complete(Unit)
        removal.await()
        evaluation.join()
        assertTrue(evaluation.isCancelled)
        assertEquals(null, service.next(continuationContext))
    }

    @Test
    fun continuationSnapshotRejectsRemovedPolicyAndKeepsReplacement(): Unit = runTest {
        val service = KcodeContinuations(Context())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        service.register(ContinuationPolicy("first", 0) { entered.complete(Unit); resume.await(); null })
        var invoked = false
        val old = service.register(ContinuationPolicy("second", 1) { invoked = true; "stale" })
        val evaluation = async { runCatching { service.next(continuationContext) } }
        entered.await()
        old.dispose()
        service.register(ContinuationPolicy("second", 1) { "replacement" })
        old.dispose()
        resume.complete(Unit)
        assertFailsWith<IllegalStateException> { evaluation.await().getOrThrow() }
        assertFalse(invoked)
        assertEquals("replacement", service.next(continuationContext))
    }

    @Test
    fun toolProviderRemovalCancelsAndWaitsForSnapshotConstruction(): Unit = runTest {
        val service = KcodeTools(Context())
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val registration = service.register("fixture") { waitForRevoke(entered, cleanup) }
        val snapshot = launch { service.snapshot(toolContext) }
        entered.await()
        val removal = async { registration.dispose() }
        runCurrent()
        assertFalse(removal.isCompleted)
        cleanup.complete(Unit)
        removal.await()
        snapshot.join()
        assertTrue(snapshot.isCancelled)
        assertTrue(service.snapshot(toolContext).tools.isEmpty())
    }

    @Test
    fun toolRemovalCancelsExecutionAndRevokesPreviouslyPublishedHandle(): Unit = runTest {
        val service = KcodeTools(Context())
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val backend = fixtureTool { waitForRevoke(entered, cleanup) }
        val registration = service.register("fixture", ToolRegistry { tool(backend) })
        val old = service.snapshot(toolContext).getTool("fixture_tool")
        val execution = launch { old.executeUnsafe(FixtureArgs("fixture")) }
        entered.await()
        val removal = async { registration.dispose() }
        runCurrent()
        assertFalse(removal.isCompleted)
        cleanup.complete(Unit)
        removal.await()
        execution.join()
        assertTrue(execution.isCancelled)
        assertFailsWith<IllegalStateException> { old.executeUnsafe(FixtureArgs("late")) }
        service.register("fixture", ToolRegistry { tool(fixtureTool { "replacement" }) })
        registration.dispose()
        assertEquals("replacement", service.snapshot(toolContext)
            .getTool("fixture_tool").executeUnsafe(FixtureArgs("fixture")))
    }

    @Test
    fun ownedToolPreservesSchemaMetadataAndCustomResultCodecs(): Unit = runTest {
        val service = KcodeTools(Context())
        val schema = fixtureTool { "fixture" }
        val attachment = MessagePart.Attachment(AttachmentSource.Image(
            content = AttachmentContent.Binary.Bytes(byteArrayOf(1, 2, 3)),
            format = "png",
            mimeType = "image/png",
        ))
        val backend = object : ToolBase<FixtureArgs, String>(
            typeToken<FixtureArgs>(), typeToken<String>(), schema.descriptor, mapOf("tag" to "fixture"),
        ) {
            override suspend fun execute(args: FixtureArgs, metadata: ToolCallMetadata) =
                "${args.command}:${metadata["call"]}"
            override fun encodeResultToString(result: String, serializer: JSONSerializer) = "custom:$result"
            override fun encodeResultToParts(result: String, serializer: JSONSerializer) = listOf(MessagePart.Text("media:$result"), attachment)
        }
        val registration = service.register("fixture", ToolRegistry { tool(backend) })
        val published = service.snapshot(toolContext).getTool("fixture_tool")
        assertSame(backend.descriptor, published.descriptor)
        assertEquals(backend.metadata, published.metadata)
        assertSame(backend.argsType, published.argsType)
        assertSame(backend.resultType, published.resultType)
        val serializer = KotlinxSerializer()
        val args = FixtureArgs("payload")
        assertEquals(args, published.decodeArgs(published.encodeArgsUnsafe(args, serializer), serializer))
        val result = published.executeUnsafe(args, ToolCallMetadata(mapOf("call" to "id")))
        assertEquals("payload:id", result)
        assertEquals("custom:payload:id", published.encodeResultToStringUnsafe(result, serializer))
        val parts = published.encodeResultToPartsUnsafe(result, serializer)
        assertEquals(listOf(MessagePart.Text("media:payload:id"), attachment), parts)
        assertSame(attachment, parts[1])
        registration.dispose()
        assertFailsWith<IllegalStateException> { published.encodeResultToPartsUnsafe(result, serializer) }
    }

    @Test
    fun toolSnapshotRejectsProviderRemovedWhilePreviousProviderRuns(): Unit = runTest {
        val service = KcodeTools(Context())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        service.register("first") { entered.complete(Unit); resume.await(); ToolRegistry { } }
        var invoked = false
        val old = service.register("second") { invoked = true; ToolRegistry { } }
        val snapshot = async { runCatching { service.snapshot(toolContext) } }
        entered.await()
        old.dispose()
        resume.complete(Unit)
        assertFailsWith<IllegalStateException> { snapshot.await().getOrThrow() }
        assertFalse(invoked)
    }

    @Test
    fun reentrantRemovalIsRejectedWithoutLosingContributions(): Unit = runTest {
        val prompts = KcodeSystemPrompt(Context())
        lateinit var promptRegistration: Disposable
        promptRegistration = prompts.register(PromptSection("fixture") { promptRegistration.dispose(); "unreachable" })
        assertFailsWith<IllegalStateException> { prompts.render(promptRequest) }
        assertEquals(listOf("fixture"), prompts.sectionIds())
        promptRegistration.dispose()

        val policies = KcodeContinuations(Context())
        lateinit var policyRegistration: Disposable
        policyRegistration = policies.register(ContinuationPolicy("fixture", 0) { policyRegistration.dispose(); "unreachable" })
        assertFailsWith<IllegalStateException> { policies.next(continuationContext) }
        policyRegistration.dispose()
        assertEquals(null, policies.next(continuationContext))

        val tools = KcodeTools(Context())
        lateinit var toolRegistration: Disposable
        toolRegistration = tools.register("fixture", ToolRegistry { tool(fixtureTool { toolRegistration.dispose(); "unreachable" }) })
        val tool = tools.snapshot(toolContext).getTool("fixture_tool")
        assertFailsWith<IllegalStateException> { tool.executeUnsafe(FixtureArgs("fixture")) }
        assertEquals(listOf("fixture"), tools.contributionIds())
        toolRegistration.dispose()
    }

    private fun fixtureTool(run: suspend () -> String) = object : SimpleTool<FixtureArgs>(
        typeToken<FixtureArgs>(), "fixture_tool", "fixture",
    ) {
        override suspend fun execute(args: FixtureArgs) = run()
    }

    @Serializable
    data class FixtureArgs(val command: String)

    private suspend fun waitForRevoke(entered: CompletableDeferred<Unit>, cleanup: CompletableDeferred<Unit>): Nothing {
        entered.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { cleanup.await() }
        }
    }

    private object IdleCoordinator : SubagentCoordinator {
        override suspend fun spawn(callerPath: String, taskName: String, message: String, forkTurns: String?): String = error("Unexpected spawn")
        override suspend fun sendMessage(callerPath: String, target: String, message: String): String = error("Unexpected message")
        override suspend fun followupTask(callerPath: String, target: String, message: String): String = error("Unexpected followup")
        override suspend fun interrupt(callerPath: String, target: String): String = error("Unexpected interrupt")
        override suspend fun list(callerPath: String, pathPrefix: String?): String = ""
        override suspend fun waitForUpdate(callerPath: String): String = ""
        override suspend fun drainMailbox(agentPath: String): String = ""
        override suspend fun continuationAfterRootResponse(): String? = null
        override suspend fun onToolUse(agentPath: String, event: ToolUseEvent) = Unit
        override suspend fun shutdown() = Unit
    }

    private companion object {
        val promptRequest = PromptAssemblyRequest(null, "fixture")
        val continuationContext = AgentContinuationContext({ null }, { null })
        val toolContext = AgentToolContext("/root", IdleCoordinator, null, null, null)
    }
}
