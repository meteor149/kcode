package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileRepositoryRepairMode
import ai.meteor.kcode.plugin.profiles.ProfileRepositoryRepairRequest
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeProfileRepositoryRecoveryTest {
    @Test
    fun checkpointRepairRetainsRecoveryAndAllocatesOnlyOnExplicitActivation(): Unit = runBlocking {
        val home = Files.createTempDirectory("native-profile-repository-recovery")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        val definition = ProfileDefinition(id = "coding", displayName = "Original", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("instance", "example.module"))),
        ))
        repository.saveDraft(definition)
        repository.saveDraft(definition.copy(displayName = "Later edit"))
        val primary = home.resolve("profiles/.profile-state.json")
        Files.writeString(primary, "{broken authority")
        var allocations = 0
        val host = createDesktopProfileHost(homeDirectory = home, profileId = "coding",
            profile = KcodePluginProfile(includeDefaults = false), moduleFactories = mapOf("example.module" to {
                kcodePlugin(PluginDescriptor("example.module", "test", "test", emptySet()), plugin<Unit> { _, _ -> allocations++ }, Unit)
            }))
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            val review = assertNotNull(host.inspectRepositoryRecovery())
            assertEquals("Original", assertNotNull(review.checkpoint).profiles.single().draftDisplayName)
            val result = host.repairRepository(ProfileRepositoryRepairRequest(review.fingerprint, ProfileRepositoryRepairMode.RestoreCheckpoint))
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertEquals(0, allocations)
            assertEquals(definition, repository.loadDraft("coding"))
            assertEquals("{broken authority", Files.readString(home.resolve("profiles/.profile-recovery/${result.evidenceId}/.profile-state.json")))
            host.prepareRecoveryMetadata()
            assertEquals(0, allocations)
            assertEquals(1, host.profileTemplates.size)
            val client = assertNotNull(host.profileCommands)
            assertEquals(null, client.catalogue().activeProfileId)
            assertEquals(ProfileCommandPhase.Succeeded, client.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("coding", ProfileSource.Draft), result.catalogue.revision,
            ))).await().phase)
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertEquals(1, allocations)
            assertFailsWith<IllegalStateException> { host.repairRepository(ProfileRepositoryRepairRequest(review.fingerprint, ProfileRepositoryRepairMode.StartEmpty)) }
        } finally { host.close(); home.toFile().deleteRecursively() }
        assertFailsWith<IllegalStateException> { host.inspectRepositoryRecovery() }
    }

    @Test
    fun emptyRepairWithoutCheckpointCanCreateAndActivateASeparateProfile(): Unit = runBlocking {
        val home = Files.createTempDirectory("native-profile-empty-recovery")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        repository.state()
        Files.writeString(home.resolve("profiles/.profile-state.json"), "{broken")
        val host = createDesktopProfileHost(homeDirectory = home, profile = KcodePluginProfile(includeDefaults = false))
        try {
            val review = assertNotNull(host.inspectRepositoryRecovery())
            assertEquals(null, review.checkpoint)
            val result = host.repairRepository(ProfileRepositoryRepairRequest(review.fingerprint, ProfileRepositoryRepairMode.StartEmpty))
            assertTrue(result.catalogue.profiles.isEmpty())
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            host.prepareRecoveryMetadata()
            val template = host.profileTemplates.single().copy(id = "repair")
            val client = assertNotNull(host.profileCommands)
            val saved = client.writeDraft(ProfileDraftWrite(template, result.catalogue.revision, createOnly = true))
            assertEquals(ProfileCommandPhase.Succeeded, client.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("repair", ProfileSource.Draft), saved.revision,
            ))).await().phase)
            assertEquals("repair", repository.selected())
        } finally { host.close(); home.toFile().deleteRecursively() }
    }
}
