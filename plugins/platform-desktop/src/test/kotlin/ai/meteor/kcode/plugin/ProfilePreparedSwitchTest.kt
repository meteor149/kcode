package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.cordis.EffectScope
import org.cordis.plugin

class ProfilePreparedSwitchTest {
    @Test
    fun candidateStartupLeavesAuthorityUntouchedUntilJointPublication(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-prepared-runtime").toFile()
        val repository = FileProfileRepository(root)
        var live = 0
        val mount = provider { live++; collect { live-- } }
        try {
            selectOld(repository)
            val before = repository.state()
            val activation = activation(repository, before.revision)
            val runtime = start(activation, mount)
            try {
                assertEquals(1, live)
                assertEquals(before, repository.state())
                assertNull(repository.loadCommitted("target"))
                val committed = activation.session.publishPreparedSwitch()
                assertEquals("target", repository.selected())
                assertEquals(committed, repository.state().selected)
                assertEquals(listOf(1L), repository.generations("target"))
                assertFailsWith<IllegalStateException> { activation.session.publishPreparedSwitch() }
                runtime.pluginManager.setEnabled("instance", false)
                assertEquals(0, live)
                assertEquals(2L, repository.state().selected?.generation)
            } finally { runtime.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun refusedPublicationCanBeRetriedWithoutReallocatingTheCandidate(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-prepared-refusal").toFile()
        val storage = FileProfileRepository(root)
        var refuse = true
        val repository = object : ProfileGenerationRepository by storage {
            override suspend fun commitAndSelect(value: CommittedProfileGeneration, expectedGeneration: Long?, expectedRevision: Long) {
                check(!refuse) { "publication refused" }
                storage.commitAndSelect(value, expectedGeneration, expectedRevision)
            }
        }
        var live = 0
        val mount = provider { live++; collect { live-- } }
        try {
            selectOld(storage)
            val before = storage.state()
            val activation = activation(repository, before.revision)
            val runtime = start(activation, mount)
            try {
                assertFailsWith<IllegalStateException> { activation.session.publishPreparedSwitch() }
                assertEquals(before, storage.state())
                assertNull(storage.loadCommitted("target"))
                assertEquals(1, live)
                refuse = false
                activation.session.publishPreparedSwitch()
                assertEquals("target", storage.selected())
                assertEquals(1, live)
            } finally { runtime.close() }
            assertEquals(0, live)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun stalePreparedRuntimeCannotDisplaceANewerAuthorityAndCanBeDiscarded(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-prepared-conflict").toFile()
        val repository = FileProfileRepository(root)
        var live = 0
        val mount = provider { live++; collect { live-- } }
        try {
            selectOld(repository)
            val activation = activation(repository, repository.state().revision)
            val runtime = start(activation, mount)
            try {
                repository.saveDraft(ProfileDefinition(id = "other"))
                val latest = repository.state()
                assertFailsWith<IllegalArgumentException> { activation.session.publishPreparedSwitch() }
                assertEquals(latest, repository.state())
                assertNull(repository.loadCommitted("target"))
                activation.session.discardPreparedSwitch()
                assertFailsWith<IllegalStateException> { activation.session.save(activation.session.load()) }
            } finally { runtime.close() }
            assertEquals(0, live)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun candidateAllocationFailureReleasesResourcesWithoutPublishingTarget(): Unit = runBlocking {
        val root = Files.createTempDirectory("profile-prepared-allocation-failure").toFile()
        val repository = FileProfileRepository(root)
        var live = 0
        val mount = provider {
            live++
            collect { live-- }
            error("allocation refused")
        }
        try {
            selectOld(repository)
            val before = repository.state()
            val activation = activation(repository, before.revision)
            assertFailsWith<IllegalStateException> { start(activation, mount) }
            assertEquals(0, live)
            assertEquals(before, repository.state())
            assertNull(repository.loadCommitted("target"))
            assertFailsWith<IllegalStateException> { activation.session.publishPreparedSwitch() }
            activation.session.discardPreparedSwitch()
        } finally { root.deleteRecursively() }
    }

    private suspend fun selectOld(repository: ProfileGenerationRepository) {
        repository.commit(CommittedProfileGeneration(generation = 1, definition = ProfileDefinition(id = "old"),
            lock = ProfileLock(), composition = PluginCompositionSnapshot()), null)
        repository.select("old")
    }

    private suspend fun activation(repository: ProfileGenerationRepository, revision: Long): ProfileActivation {
        val definition = ProfileDefinition(id = "target", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("instance", "example.provider"))),
        ))
        return ProfileActivation(ResolvedProfile(definition, ProfileCompiler().compile(definition, emptyList()),
            emptyList(), ProfileLock()), ProfileCompositionSession.prepareSwitch(repository, definition, revision))
    }

    private fun provider(apply: suspend EffectScope.() -> Unit) = kcodePlugin(
        PluginDescriptor("example.provider", "test", "test", emptySet()), plugin<Unit> { _, _ -> apply() }, Unit,
    )

    private suspend fun start(activation: ProfileActivation, mount: KcodePluginMount) = KcodePluginRuntime.create(
        KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            profileActivation = activation,
            profileBuiltinModules = listOf(mount),
        ),
    )
}
