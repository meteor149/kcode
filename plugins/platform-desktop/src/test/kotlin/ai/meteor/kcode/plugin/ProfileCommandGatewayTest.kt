package ai.meteor.kcode.plugin

import ai.meteor.kcode.KcodeAgentRuntime
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeAgents
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.profiles.KcodeProfiles
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionEdit
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableImport
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import ai.meteor.kcode.plugin.profiles.ProfilePortableExporter
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.CommittedProfileGeneration
import ai.meteor.kcode.plugin.profiles.ProfileGenerationRepository
import ai.meteor.kcode.plugin.profiles.ProfileActivation
import ai.meteor.kcode.plugin.profiles.ProfileCompiler
import ai.meteor.kcode.plugin.profiles.ProfileCompositionSession
import ai.meteor.kcode.plugin.profiles.ProfileLock
import ai.meteor.kcode.plugin.profiles.ProfileManagement
import ai.meteor.kcode.plugin.profiles.ResolvedProfile
import ai.meteor.kcode.plugin.profiles.loadProfileIntent
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.dependencies
import org.cordis.plugin

class ProfileCommandGatewayTest {
    private val configuration = ModelConfiguration(ModelProvider("test"), "test", "", 0.0)

    private val portable = """{"definition":{"id":"portable","patches":[{"type":"insert","entries":[{"id":"agent","packageId":"example.agent"},{"id":"listener","packageId":"example.listener"}]}]},"bundles":[],"lock":{}}"""

    @Test
    fun neutralExchangePublishesOnlyDraftAndSurvivesThroughExplicitActivation(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val oldClient = fixture.client
            val revision = oldClient.catalogue().revision
            val catalogue = oldClient.importPortable(ProfilePortableImport(portable, "imported", revision))
            assertEquals("old", catalogue.activeProfileId)
            assertEquals("old", fixture.repository.selected())
            assertEquals(1L, fixture.repository.loadCommitted("old")!!.generation)
            assertEquals(null, fixture.repository.loadCommitted("imported"))
            assertEquals("old", host.chatService.reply(configuration, emptyList(), "test"))
            assertFailsWith<IllegalArgumentException> {
                oldClient.importPortable(ProfilePortableImport(portable, "imported", catalogue.revision))
            }
            assertFailsWith<IllegalArgumentException> {
                oldClient.exportPortable(ProfilePortableExport(ProfileTarget("imported", ProfileSource.Draft), catalogue.revision))
            }
            val ticket = oldClient.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("imported", ProfileSource.Draft), catalogue.revision)))
            assertEquals(ProfileCommandPhase.Succeeded, withTimeout(10_000) { ticket.await() }.phase)
            assertEquals("default", host.chatService.reply(configuration, emptyList(), "test"))
            val current = fixture.client.catalogue()
            val export = ProfilePortableExport(ProfileTarget("imported"), current.revision)
            val text = fixture.client.exportPortable(export)
            assertEquals("imported", ProfilePortableExporter.decode(text).definition.id)
            assertEquals(current, fixture.client.catalogue())
            assertFailsWith<IllegalStateException> { oldClient.exportPortable(export) }
            assertFailsWith<IllegalStateException> { oldClient.importPortable(ProfilePortableImport(portable, "stale", current.revision)) }
            assertFailsWith<IllegalArgumentException> {
                fixture.client.exportPortable(export.copy(expectedRevision = revision))
            }
        } finally { fixture.close() }
    }

    @Test
    fun unknownConfigurationCannotBeApprovedByAnExportCallerAndRecoveryRetainsImport(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            assertFailsWith<IllegalArgumentException> {
                fixture.client.exportPortable(ProfilePortableExport(ProfileTarget("old"), fixture.repository.state().revision))
            }
            fixture.fail += setOf("old", "target")
            val ticket = fixture.client.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("target", ProfileSource.Draft), fixture.repository.state().revision)))
            assertEquals(ProfileCommandPhase.Failed, withTimeout(10_000) { ticket.await() }.phase)
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            val client = host.profileCommands!!
            val catalogue = client.importPortable(ProfilePortableImport(portable, "repair", client.catalogue().revision))
            assertEquals(null, catalogue.activeProfileId)
            assertEquals("old", fixture.repository.selected())
            assertEquals("repair", client.draft("repair")!!.id)
            assertEquals(null, fixture.repository.loadCommitted("repair"))
        } finally { fixture.close() }
    }

    @Test
    fun activationCancellationAtPublicationReportsCommittedSuccess(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            fixture.prepareGate = CompletableDeferred()
            val ticket = fixture.client.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("target", ProfileSource.Draft), fixture.repository.state().revision)))
            withTimeout(10_000) { fixture.prepareEntered.await() }
            fixture.afterPublication = { ticket.cancel() }
            fixture.prepareGate!!.complete(Unit)
            val result = withTimeout(10_000) { ticket.await() }
            assertEquals(ProfileCommandPhase.Succeeded, result.phase)
            assertEquals("target", result.result!!.definition.id)
            assertEquals(1L, result.result!!.generation)
            assertEquals("target", fixture.repository.selected())
            assertEquals(ProfileHostState("target"), host.state.value)
            assertEquals("target", host.chatService.reply(configuration, emptyList(), "test"))
            assertEquals(1, fixture.agents)
            assertEquals(1, fixture.listeners)
        } finally { fixture.close() }
    }

    @Test
    fun editCancellationAtPublicationReportsCommittedSuccess(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            fixture.applyGate = CompletableDeferred()
            val ticket = fixture.client.submit(ProfileCommand.Edit(ProfileCompositionEdit("old", 1, listOf(
                ProfileOperation.Configure("agent", JsonPrimitive("paused"), "string"),
            ))))
            withTimeout(10_000) { fixture.applyEntered.await() }
            fixture.afterPublication = { ticket.cancel() }
            fixture.applyGate!!.complete(Unit)
            val result = withTimeout(10_000) { ticket.await() }
            assertEquals(ProfileCommandPhase.Succeeded, result.phase)
            assertEquals("old", result.result!!.definition.id)
            assertEquals(2L, result.result!!.generation)
            assertEquals(result.result!!.definition, fixture.repository.loadCommitted("old")!!.definition)
            assertEquals(ProfileHostState("old"), host.state.value)
            assertEquals("paused", host.chatService.reply(configuration, emptyList(), "test"))
            assertEquals(1, fixture.agents)
            assertEquals(1, fixture.listeners)
        } finally { fixture.close() }
    }

    @Test
    fun acceptedActivationSurvivesObserverCancellationAndWithdrawsOldClient(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            val oldClient = fixture.client
            fixture.prepareGate = CompletableDeferred()
            val ticket = oldClient.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("target", ProfileSource.Draft), fixture.repository.state().revision)))
            withTimeout(10_000) { fixture.prepareEntered.await() }
            val observer = async(start = CoroutineStart.UNDISPATCHED) { ticket.await() }
            observer.cancelAndJoin()
            fixture.prepareGate!!.complete(Unit)
            val result = withTimeout(10_000) { ticket.await() }
            assertEquals(ProfileCommandPhase.Succeeded, result.phase)
            assertEquals("target", result.result!!.definition.id)
            assertEquals(ProfileHostState("target"), host.state.value)
            assertEquals(1, fixture.agents)
            assertEquals(1, fixture.listeners)
            assertFailsWith<IllegalStateException> { oldClient.catalogue() }
            assertFailsWith<IllegalStateException> { oldClient.submit(ProfileCommand.Edit(ProfileCompositionEdit("old", 1, emptyList()))) }
            assertEquals("target", fixture.client.catalogue().activeProfileId)
            assertEquals(listOf("agent"), fixture.client.modules().single { it.id == "example.agent" }.instances)
            assertEquals(result, withTimeout(10_000) { fixture.client.commands.first { values -> values.any { it.id == ticket.id && it.phase == ProfileCommandPhase.Succeeded } } }.single())
        } finally { fixture.close() }
    }

    @Test
    fun serializedCommandsDetachEditsAndQueuedCancellationDoesNotPublish(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            fixture.applyGate = CompletableDeferred()
            val first = fixture.client.submit(ProfileCommand.Edit(ProfileCompositionEdit("old", 1, listOf(
                ProfileOperation.Configure("agent", JsonPrimitive("paused"), "string"),
            ))))
            withTimeout(10_000) { fixture.applyEntered.await() }
            val operations = mutableListOf<ProfileOperation>(ProfileOperation.Disable("listener"))
            val second = fixture.client.submit(ProfileCommand.Edit(ProfileCompositionEdit("old", 2, operations)))
            operations.clear()
            operations += ProfileOperation.Remove("agent")
            val cancelled = fixture.client.submit(ProfileCommand.Edit(ProfileCompositionEdit("old", 3, listOf(ProfileOperation.Enable("listener")))))
            cancelled.cancel()
            fixture.applyGate!!.complete(Unit)
            assertEquals(ProfileCommandPhase.Succeeded, withTimeout(10_000) { first.await() }.phase)
            assertEquals(ProfileCommandPhase.Succeeded, withTimeout(10_000) { second.await() }.phase)
            assertEquals(ProfileCommandPhase.Cancelled, withTimeout(10_000) { cancelled.await() }.phase)
            val committed = fixture.repository.loadCommitted("old")!!
            assertEquals(3L, committed.generation)
            assertEquals(ProfileOperation.Disable("listener"), committed.definition.patches.last())
            assertEquals(1, fixture.agents)
            assertEquals(0, fixture.listeners)
            assertEquals(3, withTimeout(10_000) { host.profileCommands!!.commands.first { it.size == 3 } }.size)
        } finally { fixture.close() }
    }

    @Test
    fun hostClosureCancelsRunningAndQueuedCommandsAndCompletesAllHandles(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            fixture.applyGate = CompletableDeferred()
            val running = fixture.client.submit(ProfileCommand.Edit(ProfileCompositionEdit("old", 1, listOf(
                ProfileOperation.Configure("agent", JsonPrimitive("paused"), "string"),
            ))))
            withTimeout(10_000) { fixture.applyEntered.await() }
            val queued = (1..31).map { fixture.client.submit(ProfileCommand.Edit(ProfileCompositionEdit("old", 1, emptyList()))) }
            assertFailsWith<IllegalStateException> { fixture.client.submit(ProfileCommand.Edit(ProfileCompositionEdit("old", 1, emptyList()))) }
            withTimeout(10_000) { host.close() }
            assertEquals(ProfileCommandPhase.Cancelled, running.await().phase)
            queued.forEach { assertEquals(ProfileCommandPhase.Cancelled, it.await().phase) }
            assertEquals(1L, fixture.repository.loadCommitted("old")!!.generation)
            assertEquals(ProfileManagementPhase.Closed, host.profileCommands!!.state.value.phase)
            assertEquals(0, fixture.agents)
            assertEquals(0, fixture.listeners)
        } finally { fixture.close() }
    }

    @Test
    fun failedRestorationKeepsHostGatewayAvailableForRecoveryCommands(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val host = fixture.start()
            fixture.fail += setOf("old", "target")
            val failed = fixture.client.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("target", ProfileSource.Draft), fixture.repository.state().revision)))
            assertEquals(ProfileCommandPhase.Failed, withTimeout(10_000) { failed.await() }.phase)
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            val client = host.profileCommands!!
            assertEquals(null, client.catalogue().activeProfileId)
            fixture.fail.clear()
            val recovered = client.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("old"), fixture.repository.state().revision)))
            assertEquals(ProfileCommandPhase.Succeeded, withTimeout(10_000) { recovered.await() }.phase)
            assertEquals(ProfileHostState("old"), host.state.value)
            withTimeout(10_000) { client.state.first { it.phase == ProfileManagementPhase.Ready } }
            assertEquals(2L, fixture.repository.loadCommitted("old")!!.generation)
        } finally { fixture.close() }
    }

    private class Fixture {
        val root = Files.createTempDirectory("kcode-profile-commands").toFile()
        private val storage = FileProfileRepository(root)
        var afterPublication: (() -> Unit)? = null
        val repository = object : ProfileGenerationRepository by storage {
            override suspend fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?) {
                storage.commit(value, expectedGeneration)
                afterPublication?.invoke()
            }

            override suspend fun commitAndSelect(value: CommittedProfileGeneration, expectedGeneration: Long?, expectedRevision: Long) {
                storage.commitAndSelect(value, expectedGeneration, expectedRevision)
                afterPublication?.invoke()
            }
        }
        val gateway = ProfileCommandGateway()
        lateinit var client: ProfileManagementClient
        var host: KcodeProfileHost? = null
        var agents = 0
        var listeners = 0
        val fail = mutableSetOf<String>()
        var prepareGate: CompletableDeferred<Unit>? = null
        val prepareEntered = CompletableDeferred<Unit>()
        var applyGate: CompletableDeferred<Unit>? = null
        val applyEntered = CompletableDeferred<Unit>()

        private fun definition(id: String) = ProfileDefinition(id = id, patches = listOf(ProfileOperation.Insert(listOf(
            ProfileEntry("agent", "example.agent", JsonPrimitive(id), configurationKind = "string"),
            ProfileEntry("listener", "example.listener"),
        ))))

        private fun activation(definition: ProfileDefinition, session: ProfileCompositionSession) = ProfileActivation(
            ResolvedProfile(definition, ProfileCompiler().compile(definition, emptyList()), emptyList(), ProfileLock()), session,
        )

        private suspend fun prepare(request: ProfileActivationRequest): ProfileActivation {
            require(repository.state().revision == request.expectedRevision) { "Stale repository revision" }
            if (request.target.profileId == "target" && prepareGate != null) {
                prepareEntered.complete(Unit)
                prepareGate!!.await()
            }
            val intent = loadProfileIntent(repository, request.target)
            return activation(intent.definition, ProfileCompositionSession.prepareCandidate(repository, intent.definition,
                request.expectedRevision, intent.base?.composition ?: ai.meteor.kcode.plugin.api.PluginCompositionSnapshot(), emptyList()))
        }

        suspend fun start(): KcodeProfileHost {
            repository.saveDraft(definition("target"))
            val old = definition("old")
            val initial = create(activation(old, ProfileCompositionSession.open(repository, old)))
            repository.select("old")
            val factory = object : ProfileRuntimeFactory {
                override suspend fun prepare(id: String): PreparedProfileRuntime = prepare(ProfileActivationRequest(
                    ProfileTarget(id, if (repository.loadCommitted(id) == null) ProfileSource.Draft else ProfileSource.Committed),
                    repository.state().revision))
                override suspend fun prepare(request: ProfileActivationRequest): PreparedProfileRuntime {
                    val activation = this@Fixture.prepare(request)
                    return PreparedProfileRuntime(activation) { create(activation) }
                }
            }
            return KcodeProfileHost(initial, "old", factory, ProfileManagement(repository, { emptyList() }, ::prepare), gateway)
                .also { host = it; gateway.bind(it) }
        }

        private suspend fun create(activation: ProfileActivation): KcodeAgentRuntime {
            val agent = kcodePlugin(PluginDescriptor("example.agent", "test", "test", emptySet()), plugin<String> { ctx, value ->
                agents++
                collect { agents-- }
                check(value !in fail) { "Agent allocation refused" }
                if (value == "paused" && applyGate != null) { applyEntered.complete(Unit); applyGate!!.await() }
                KcodeAgents(ctx, object : ChatService {
                    override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String) = value
                })
            }, "default")
            val listener = kcodePlugin(PluginDescriptor("example.listener", "test", "test", emptySet()),
                plugin<Unit>(inject = dependencies(KcodeProfiles.Key)) { ctx, _ ->
                    listeners++
                    collect { listeners-- }
                    client = ctx.require(KcodeProfiles.Key).client
                }, Unit)
            val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                profileActivation = activation, profileBuiltinModules = listOf(agent, listener),
                featurePlugins = listOf(gateway.pluginMount()),
            ))
            return KcodeAgentRuntime(runtime.chatService, pluginManager = runtime.pluginManager, owner = runtime, applicationContent = runtime)
        }

        suspend fun close() {
            try { host?.close() ?: gateway.close() } finally { root.deleteRecursively() }
        }
    }
}
