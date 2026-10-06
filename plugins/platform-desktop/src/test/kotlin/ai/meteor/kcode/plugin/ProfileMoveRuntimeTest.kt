package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionEdit
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.CommittedProfileGeneration
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileActivation
import ai.meteor.kcode.plugin.profiles.ProfileCompiler
import ai.meteor.kcode.plugin.profiles.ProfileCompositionSession
import ai.meteor.kcode.plugin.profiles.ProfileGenerationRepository
import ai.meteor.kcode.plugin.profiles.ProfileLock
import ai.meteor.kcode.plugin.profiles.ResolvedProfile
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.ServiceKey
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ProfileMoveRuntimeTest {
    @Test
    fun moveRebindsContextPersistsAndRestartsFromCommittedIntent(): Unit = runBlocking {
        val fixture = Fixture()
        var runtime = fixture.start()
        try {
            assertEquals(listOf("source"), fixture.observed)
            val before = assertNotNull(runtime.pluginManager.currentProfile())
            val move = ProfileOperation.Move("traveller", "destination", 1)
            val after = runtime.pluginManager.editProfile(ProfileCompositionEdit("moving", before.generation, listOf(move)))
            assertEquals(listOf("source", "destination"), fixture.observed)
            assertEquals(1, fixture.consumers)
            assertEquals(2, fixture.providers)
            assertEquals(move, after.definition.patches.last())
            assertEquals(after.definition, fixture.storage.loadCommitted("moving")!!.definition)
            assertEquals(PluginState.Active, runtime.inventory.snapshot().single { it.id == "traveller" }.state)
            runtime.close()
            runtime = fixture.start()
            assertEquals("destination", fixture.observed.last())
            assertEquals(after.definition, runtime.pluginManager.currentProfile()!!.definition)
            assertEquals(1, fixture.consumers)
            assertEquals(2, fixture.providers)
        } finally { runtime.close(); fixture.remove() }
    }

    @Test
    fun invalidParentAndFailedPublicationPreserveGenerationAndOldRealm(): Unit = runBlocking {
        val fixture = Fixture()
        val runtime = fixture.start()
        try {
            val before = assertNotNull(runtime.pluginManager.currentProfile())
            assertFailsWith<IllegalArgumentException> {
                runtime.pluginManager.editProfile(ProfileCompositionEdit("moving", before.generation,
                    listOf(ProfileOperation.Move("source", "source"))))
            }
            assertEquals(listOf("source"), fixture.observed)
            fixture.refuse = true
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.editProfile(ProfileCompositionEdit("moving", before.generation,
                    listOf(ProfileOperation.Move("traveller", "destination"))))
            }
            assertEquals(before, runtime.pluginManager.currentProfile())
            assertEquals(before.definition, fixture.storage.loadCommitted("moving")!!.definition)
            assertEquals("source", fixture.observed.last())
            assertEquals(1, fixture.consumers)
            assertEquals(2, fixture.providers)
            fixture.refuse = false
            runtime.pluginManager.editProfile(ProfileCompositionEdit("moving", before.generation,
                listOf(ProfileOperation.Move("traveller", "destination"))))
            assertEquals("destination", fixture.observed.last())
        } finally { runtime.close(); fixture.remove() }
    }

    @Test
    fun movingIntoDisabledGroupWithdrawsConsumerAndMovingBackRecoversIt(): Unit = runBlocking {
        val fixture = Fixture()
        val runtime = fixture.start()
        try {
            var current = assertNotNull(runtime.pluginManager.currentProfile())
            current = runtime.pluginManager.editProfile(ProfileCompositionEdit("moving", current.generation, listOf(
                ProfileOperation.Disable("destination"), ProfileOperation.Move("traveller", "destination"),
            )))
            assertEquals(0, fixture.consumers)
            assertEquals(1, fixture.providers)
            assertEquals(listOf("source"), fixture.observed)
            assertEquals(PluginState.Disabled, runtime.inventory.snapshot().single { it.id == "traveller" }.state)
            runtime.pluginManager.editProfile(ProfileCompositionEdit("moving", current.generation,
                listOf(ProfileOperation.Move("traveller", "source"))))
            assertEquals(listOf("source", "source"), fixture.observed)
            assertEquals(1, fixture.consumers)
            assertEquals(1, fixture.providers)
        } finally { runtime.close(); fixture.remove() }
    }

    private class Fixture {
        private val root = Files.createTempDirectory("kcode-profile-move").toFile()
        val storage = FileProfileRepository(root)
        var refuse = false
        private val repository = object : ProfileGenerationRepository by storage {
            override suspend fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?) {
                check(!refuse) { "Publication refused" }
                storage.commit(value, expectedGeneration)
            }
        }
        val observed = mutableListOf<String>()
        var consumers = 0
        var providers = 0
        private val answer = ServiceKey<String>("profileMoveAnswer")
        private val provider = kcodePlugin(descriptor("example.provider"), plugin<String> { context, value ->
            providers++
            collect { providers-- }
            context.provide(answer, value)
        }, "default")
        private val consumer = kcodePlugin(descriptor("example.consumer"), plugin<Unit>(inject = dependencies(answer)) { context, _ ->
            consumers++
            collect { consumers-- }
            observed += context.require(answer)
        }, Unit)

        private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
        private fun group(id: String, traveller: Boolean) = ProfileEntry(id, "core.group", children = listOf(
            ProfileEntry("$id-provider", "example.provider", JsonPrimitive(id), configurationKind = "string"),
        ) + if (traveller) listOf(ProfileEntry("traveller", "example.consumer")) else emptyList(),
            isolate = mapOf(answer.name to null))

        suspend fun start(): KcodePluginRuntime {
            val definition = storage.loadCommitted("moving")?.definition ?: ProfileDefinition(id = "moving", patches = listOf(
                ProfileOperation.Insert(listOf(group("destination", false), group("source", true))),
            ))
            val activation = ProfileActivation(ResolvedProfile(definition,
                ProfileCompiler().compile(definition, emptyList()).requireValid(), emptyList(), ProfileLock()),
                ProfileCompositionSession.open(repository, definition))
            return KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                profileActivation = activation, profileBuiltinModules = listOf(provider, consumer),
            ))
        }

        fun remove() {
            assertEquals(0, consumers)
            assertEquals(0, providers)
            check(root.toPath().toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            root.deleteRecursively()
        }
    }
}
