package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentRuntimeOwner
import ai.meteor.kcode.AgentConversationOverlayController
import ai.meteor.kcode.AgentConversationOverlayTurn
import ai.meteor.kcode.KcodeAgentRuntime
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.profiles.CommittedProfileGeneration
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileActivation
import ai.meteor.kcode.plugin.profiles.ProfileCompiler
import ai.meteor.kcode.plugin.profiles.ProfileCompositionSession
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.profiles.ProfileGenerationRepository
import ai.meteor.kcode.plugin.profiles.ProfileLock
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.ResolvedProfile
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.cordis.plugin

class ProfileHostTest {
    private val configuration = ModelConfiguration(ModelProvider("test"), "test", "", 0.0)

    @Test
    fun stableFacadesSwitchCompleteRuntimesAndDisposeOldResources(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val facade = host.runtime
            assertEquals("old", facade.chatService.reply(configuration, emptyList(), "hello"))
            host.switchTo("target")
            assertSame(facade, host.runtime)
            assertEquals("target", facade.chatService.reply(configuration, emptyList(), "hello"))
            assertEquals("target", fixture.repository.selected())
            assertEquals(listOf("target"), fixture.live)
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            host.close()
            host.close()
            assertTrue(fixture.live.isEmpty())
            assertFailsWith<IllegalStateException> { facade.chatService.reply(configuration, emptyList(), "late") }
        } finally { fixture.close() }
    }

    @Test
    fun failedTargetAllocationReconstructsOldLockWithoutAdvancingItsHistory(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val before = fixture.repository.state()
            fixture.failAllocation += "target"
            assertFailsWith<IllegalStateException> { host.switchTo("target") }
            assertEquals(before, fixture.repository.state())
            assertEquals(listOf(1L), fixture.repository.generations("old"))
            assertEquals(listOf("old"), fixture.live)
            assertEquals("old", host.runtime.chatService.reply(configuration, emptyList(), "retry"))
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            fixture.failAllocation.clear()
            host.switchTo("target")
            assertEquals("target", host.state.value.profileId)
        } finally { fixture.close() }
    }

    @Test
    fun refusedPublicationRestoresOldRuntimeAndKeepsSelectionAndHistory(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val before = fixture.repository.state()
            fixture.refusePublication = true
            assertFailsWith<IllegalStateException> { host.switchTo("target") }
            assertEquals(before, fixture.repository.state())
            assertTrue(fixture.repository.generations("target").isEmpty())
            assertEquals(listOf("old"), fixture.live)
            assertEquals("old", host.runtime.chatService.reply(configuration, emptyList(), "retry"))
        } finally { fixture.close() }
    }

    @Test
    fun activeTurnsRequireExplicitCancellationAndJoinBeforeResourcesClose(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val started = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            fixture.reply = { id ->
                if (id == "old") {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { finished.complete(Unit) }
                } else id
            }
            val turn = async { host.runtime.chatService.reply(configuration, emptyList(), "hold") }
            started.await()
            assertFailsWith<IllegalStateException> { host.switchTo("target") }
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            host.switchTo("target", cancelActive = true)
            assertTrue(turn.isCancelled)
            assertTrue(finished.isCompleted)
            assertEquals(listOf("target"), fixture.live)
            assertEquals("target", host.runtime.chatService.reply(configuration, emptyList(), "next"))
        } finally { fixture.close() }
    }

    @Test
    fun suspendedCandidateCancellationRestoresOldRuntimeAndReopensAdmission(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val before = fixture.repository.state()
            fixture.suspendAllocation = CompletableDeferred()
            val change = async { host.switchTo("target") }
            fixture.suspendAllocation!!.await()
            assertEquals(ProfileHostPhase.Switching, host.state.value.phase)
            assertFailsWith<IllegalStateException> { host.runtime.chatService.reply(configuration, emptyList(), "during") }
            change.cancelAndJoin()
            assertEquals(before, fixture.repository.state())
            assertEquals(listOf("old"), fixture.live)
            assertEquals("old", host.runtime.chatService.reply(configuration, emptyList(), "after"))
        } finally { fixture.close() }
    }

    @Test
    fun recoveryFailureClosesAdmissionAndRetainsOriginalAndRecoveryErrors(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val before = fixture.repository.state()
            fixture.failAllocation += setOf("target", "old")
            val failure = assertFailsWith<IllegalStateException> { host.switchTo("target") }
            assertEquals(before, fixture.repository.state())
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertSame(failure, host.state.value.failure)
            assertTrue(failure.suppressedExceptions.isNotEmpty())
            assertTrue(fixture.live.isEmpty())
            assertFailsWith<IllegalStateException> { host.runtime.chatService.reply(configuration, emptyList(), "late") }
        } finally { fixture.close() }
    }

    @Test
    fun providerCallbacksCannotSwitchOrCloseTheirOwnHost(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            fixture.reply = {
                assertFailsWith<IllegalStateException> { host.switchTo("target", cancelActive = true) }
                assertFailsWith<IllegalStateException> { host.close() }
                "guarded"
            }
            assertEquals("guarded", host.runtime.chatService.reply(configuration, emptyList(), "callback"))
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
        } finally { fixture.close() }
    }

    @Test
    fun publicationBoundaryCancellationKeepsTheCommittedTargetLive(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            lateinit var change: Deferred<Unit>
            fixture.afterPublication = { change.cancel() }
            change = async { host.switchTo("target") }
            change.join()
            assertTrue(change.isCancelled)
            assertEquals("target", fixture.repository.selected())
            assertEquals(ProfileHostState("target"), host.state.value)
            assertEquals(listOf("target"), fixture.live)
            assertEquals("target", host.runtime.chatService.reply(configuration, emptyList(), "after"))
        } finally { fixture.close() }
    }

    @Test
    fun overlayLeasesAreWithdrawnAndForegroundStateSurvivesSwitching(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.overlay = true
        try {
            val host = fixture.start()
            val controller = host.runtime.conversationOverlayController!!
            val turn = controller.startTurn(emptyList())
            assertFailsWith<IllegalStateException> { host.switchTo("target") }
            controller.setHostForeground(false)
            host.switchTo("target", cancelActive = true)
            assertEquals(1, fixture.overlayFinishes)
            assertSame(controller, host.runtime.conversationOverlayController)
            assertEquals("target" to false, fixture.foregroundEvents.last())
            assertFailsWith<IllegalStateException> { turn.update(emptyList()) }
            assertEquals(0, fixture.overlayUpdates)
            turn.finish()
            assertEquals(1, fixture.overlayFinishes)
            controller.startTurn(emptyList()).finish()
            assertEquals(2, fixture.overlayFinishes)
        } finally { fixture.close() }
    }

    @Test
    fun preparationFailureLeavesOldResourcesAndAuthorityUntouched(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val before = fixture.repository.state()
            assertFailsWith<IllegalStateException> { host.switchTo("missing") }
            assertEquals(before, fixture.repository.state())
            assertEquals(listOf("old"), fixture.live)
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertEquals("old", host.runtime.chatService.reply(configuration, emptyList(), "after"))
        } finally { fixture.close() }
    }

    @Test
    fun oldRuntimeClosureFailureRequiresRecoveryInsteadOfAllocatingAnotherOwner(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val before = fixture.repository.state()
            fixture.failClosure += "old"
            assertFailsWith<IllegalStateException> { host.switchTo("target") }
            assertEquals(before, fixture.repository.state())
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertEquals(listOf("old"), fixture.allocations)
            assertFailsWith<IllegalStateException> { host.runtime.pluginManager!!.installed() }
        } finally { fixture.close() }
    }

    private class Fixture {
        val root = Files.createTempDirectory("kcode-profile-host").toFile()
        val storage = FileProfileRepository(root)
        var refusePublication = false
        var afterPublication: suspend () -> Unit = {}
        val repository = object : ProfileGenerationRepository by storage {
            override suspend fun commitAndSelect(value: CommittedProfileGeneration, expectedGeneration: Long?, expectedRevision: Long) {
                check(!refusePublication) { "publication refused" }
                storage.commitAndSelect(value, expectedGeneration, expectedRevision)
                afterPublication()
            }
        }
        val live = mutableListOf<String>()
        val allocations = mutableListOf<String>()
        val failAllocation = mutableSetOf<String>()
        val failClosure = mutableSetOf<String>()
        var suspendAllocation: CompletableDeferred<Unit>? = null
        var reply: suspend (String) -> String = { it }
        var host: KcodeProfileHost? = null
        var overlay = false
        var overlayFinishes = 0
        var overlayUpdates = 0
        val foregroundEvents = mutableListOf<Pair<String, Boolean>>()

        private fun definition(id: String) = ProfileDefinition(id = id, patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("agent", "example.agent"))),
        ))

        suspend fun start(): KcodeProfileHost {
            val definition = definition("old")
            repository.saveDraft(definition("target"))
            val activation = activation(definition, ProfileCompositionSession.open(repository, definition))
            val initial = create(activation)
            repository.select("old")
            return KcodeProfileHost(initial, "old", ProfileRuntimeFactory { id ->
                val before = repository.state()
                val selected = repository.loadCommitted(id)?.definition ?: checkNotNull(repository.loadDraft(id))
                val prepared = activation(selected, ProfileCompositionSession.prepareSwitch(repository, selected, before.revision))
                PreparedProfileRuntime(prepared) { create(prepared) }
            }).also { host = it }
        }

        private fun activation(definition: ProfileDefinition, session: ProfileCompositionSession) = ProfileActivation(
            ResolvedProfile(definition, ProfileCompiler().compile(definition, emptyList()), emptyList(), ProfileLock()), session,
        )

        private suspend fun create(activation: ProfileActivation): KcodeAgentRuntime {
            val id = activation.resolved.definition.id
            val service = object : ChatService {
                override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String) = this@Fixture.reply(id)
            }
            val mount = kcodePlugin(PluginDescriptor("example.agent", "test", "test", emptySet()), plugin<Unit> { context, _ ->
                live += id
                allocations += id
                collect { live.remove(id) }
                if (id in failAllocation) error("allocation refused for $id")
                if (id == "target" && suspendAllocation != null) {
                    suspendAllocation!!.complete(Unit)
                    awaitCancellation()
                }
                KcodeAgents(context, service)
            }, Unit)
            val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                profileActivation = activation,
                profileBuiltinModules = listOf(mount),
            ))
            val controller = if (!overlay) null else object : AgentConversationOverlayController {
                override suspend fun startTurn(initialMessages: List<ChatMessage>) = object : AgentConversationOverlayTurn {
                    override suspend fun update(messages: List<ChatMessage>) { overlayUpdates++ }
                    override suspend fun finish() { overlayFinishes++ }
                }
                override suspend fun setHostForeground(isForeground: Boolean) { foregroundEvents += id to isForeground }
            }
            return KcodeAgentRuntime(runtime.chatService, conversationOverlayController = controller, pluginManager = runtime.pluginManager,
                owner = AgentRuntimeOwner {
                    runtime.close()
                    if (id in failClosure) error("closure refused for $id")
                }, applicationContent = runtime)
        }

        suspend fun close() {
            try { host?.close() } finally { root.deleteRecursively() }
        }
    }
}
