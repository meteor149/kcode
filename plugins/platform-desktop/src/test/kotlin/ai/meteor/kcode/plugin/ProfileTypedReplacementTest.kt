package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.CommittedProfileGeneration
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileActivation
import ai.meteor.kcode.plugin.profiles.ProfileCompiler
import ai.meteor.kcode.plugin.profiles.ProfileCompositionSession
import ai.meteor.kcode.plugin.profiles.ProfileLock
import ai.meteor.kcode.plugin.profiles.ProfileRepository
import ai.meteor.kcode.plugin.profiles.ResolvedProfile
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.cordis.ConfigValidator
import org.cordis.ServiceKey
import org.cordis.dependencies
import org.cordis.plugin

class ProfileTypedReplacementTest {
    @Test
    fun replacementPreservesGroupedInstancesAndRestartRequiresSelectedCode(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-typed-replacement").toFile()
        val repository = FileProfileRepository(root)
        val answer = ServiceKey<String>("typedReplacementAnswer")
        val observed = mutableListOf<String>()
        val live = mutableListOf<String>()
        var unrelatedAllocations = 0
        fun provider(id: String, version: String) = kcodePlugin(descriptor(id, version),
            plugin<String>(validator = ConfigValidator { it }) { context, value ->
                val label = "$version:$value"
                live += label
                collect { live.remove(label) }
                context.provide(answer, label)
            }, "$version-default")
        val old = provider("example.provider", "old")
        val replacement = provider("example.alternate", "new")
        val consumer = kcodePlugin(descriptor("example.consumer"),
            plugin<Unit>(inject = dependencies(answer)) { context, _ -> observed += context.require(answer) }, Unit)
        val unrelated = kcodePlugin(descriptor("example.unrelated"), plugin<Unit> { _, _ -> unrelatedAllocations++ }, Unit)
        fun group(id: String, value: String) = ProfileEntry(id, "core.group", children = listOf(
            ProfileEntry("$id-provider", old.descriptor.id, JsonPrimitive(value), configurationKind = "string"),
            ProfileEntry("$id-consumer", consumer.descriptor.id),
        ), isolate = mapOf(answer.name to null))
        val definition = definition(group("first", "one"), group("second", "two"),
            ProfileEntry("disabled", old.descriptor.id, JsonPrimitive("disabled"), enabled = false, configurationKind = "string",
                isolate = mapOf(answer.name to null)),
            ProfileEntry("default", old.descriptor.id, isolate = mapOf(answer.name to null)), ProfileEntry("unrelated", unrelated.descriptor.id))
        val runtime = start(repository, definition, listOf(old, consumer, unrelated))
        try {
            runtime.replacePlugin(old.descriptor.id, replacement)
            assertEquals(listOf("new:one", "new:two", "new:new-default"), live)
            assertTrue("new:one" in observed && "new:two" in observed)
            assertEquals(1, unrelatedAllocations)
            val committed = repository.loadCommitted("test")!!
            assertEquals(2L, committed.generation)
            assertEquals(listOf("first-provider", "second-provider", "disabled", "default"),
                committed.definition.patches.drop(1).map { (it as ProfileOperation.Replace).target })
            assertTrue(committed.definition.patches.drop(1).all {
                it is ProfileOperation.Replace && it.packageId == replacement.descriptor.id && it.expectedPackageId == old.descriptor.id
            })
            assertEquals(PluginState.Disabled, runtime.inventory.snapshot().single { it.id == "disabled" }.state)
            assertEquals("new", runtime.inventory.snapshot().single { it.id == "first-provider" }.version)
            assertTrue(runtime.inventory.snapshot().none { it.id == old.descriptor.id })
            runtime.pluginManager.setEnabled("disabled", true)
            assertTrue("new:disabled" in live)
        } finally { runtime.close() }
        try {
            val committed = repository.loadCommitted("test")!!
            assertTrue(live.isEmpty())
            assertFailsWith<IllegalArgumentException> {
                start(repository, committed.definition, listOf(old, consumer, unrelated))
            }
            assertTrue(live.isEmpty())
            val reopened = start(repository, committed.definition, listOf(old, replacement, consumer, unrelated))
            try {
                assertTrue(live.all { it.startsWith("new:") })
                assertEquals(4, live.size)
                val conflicting = provider(replacement.descriptor.id, "different")
                assertFailsWith<IllegalArgumentException> { reopened.replacePlugin(old.descriptor.id, conflicting) }
            } finally { reopened.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun validationAllocationAndPublicationFailuresRestoreCodeIntentAndBindings(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-typed-rollback").toFile()
        val storage = FileProfileRepository(root)
        var refusePublication = false
        val repository = object : ProfileRepository by storage {
            override suspend fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?) {
                check(!refusePublication) { "publication refused" }
                storage.commit(value, expectedGeneration)
            }
        }
        val live = mutableListOf<String>()
        var oldReleases = 0
        val old = kcodePlugin(descriptor("example.provider"), plugin<String> { _, value ->
            live += "old:$value"
            collect { live.remove("old:$value"); oldReleases++ }
        }, "default")
        fun replacement(fail: Boolean = false) = kcodePlugin(descriptor("example.alternate"), plugin<String> { _, value ->
            live += "new:$value"
            collect { live.remove("new:$value") }
            check(!fail) { "allocation refused" }
        }, "default")
        val runtime = start(repository, definition(ProfileEntry("instance", old.descriptor.id, JsonPrimitive("value"), configurationKind = "string")), listOf(old))
        try {
            val before = storage.loadCommitted("test")!!
            val invalid = kcodePlugin(descriptor("example.alternate"),
                plugin<String>(validator = ConfigValidator { require(it != "value"); it }) { _, _ -> error("must not allocate") }, "default")
            assertFailsWith<IllegalArgumentException> { runtime.replacePlugin(old.descriptor.id, invalid) }
            assertEquals(0, oldReleases)
            assertEquals(before, storage.loadCommitted("test"))
            assertFailsWith<IllegalStateException> { runtime.replacePlugin(old.descriptor.id, replacement(fail = true)) }
            assertEquals(listOf("old:value"), live)
            assertEquals(before, storage.loadCommitted("test"))
            refusePublication = true
            assertFailsWith<IllegalStateException> { runtime.replacePlugin(old.descriptor.id, replacement()) }
            assertEquals(listOf("old:value"), live)
            assertEquals(before, storage.loadCommitted("test"))
            assertEquals("test", runtime.inventory.snapshot().single { it.id == "instance" }.version)
            refusePublication = false
            val accepted = replacement()
            runtime.replacePlugin(old.descriptor.id, accepted)
            assertEquals(listOf("new:value"), live)
            assertEquals(2L, storage.loadCommitted("test")!!.generation)
            assertEquals(listOf(1L, 2L), storage.generations("test"))
            assertFailsWith<IllegalArgumentException> { runtime.replacePlugin(accepted) }
            assertFailsWith<IllegalArgumentException> { runtime.replacePlugin("missing", old) }
            assertEquals(2L, storage.loadCommitted("test")!!.generation)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    @Test
    fun cancelledReplacementWithdrawsCandidateAndRestoresPreviousInstances(): Unit = runBlocking {
        val root = Files.createTempDirectory("kcode-profile-typed-cancel").toFile()
        val repository = FileProfileRepository(root)
        val live = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val old = kcodePlugin(descriptor("example.provider"), plugin<Unit> { _, _ ->
            live += "old"
            collect { live.remove("old") }
        }, Unit)
        val replacement = kcodePlugin(descriptor("example.alternate"), plugin<Unit> { _, _ ->
            live += "new"
            collect { live.remove("new") }
            entered.complete(Unit)
            awaitCancellation()
        }, Unit)
        val runtime = start(repository, definition(ProfileEntry("instance", old.descriptor.id)), listOf(old))
        try {
            val before = repository.loadCommitted("test")!!
            val change = async { runtime.replacePlugin(old.descriptor.id, replacement) }
            entered.await()
            change.cancelAndJoin()
            assertEquals(listOf("old"), live)
            assertEquals(before, repository.loadCommitted("test"))
            assertEquals(PluginState.Active, runtime.inventory.snapshot().single { it.id == "instance" }.state)
        } finally { runtime.close(); root.deleteRecursively() }
    }

    private suspend fun start(repository: ProfileRepository, definition: ProfileDefinition, modules: List<KcodePluginMount>): KcodePluginRuntime =
        KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            profileActivation = ProfileActivation(
                ResolvedProfile(definition, ProfileCompiler().compile(definition, emptyList()).requireValid(), emptyList(), ProfileLock()),
                ProfileCompositionSession.open(repository, definition),
            ),
            profileBuiltinModules = modules,
        ))

    private fun definition(vararg entries: ProfileEntry) = ProfileDefinition(id = "test",
        patches = listOf(ProfileOperation.Insert(entries.toList())))

    private fun descriptor(id: String, version: String = "test") = PluginDescriptor(id, version, "test", emptySet())
}
