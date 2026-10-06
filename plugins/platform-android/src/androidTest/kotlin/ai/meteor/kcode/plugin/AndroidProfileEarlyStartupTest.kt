package ai.meteor.kcode.plugin

import ai.meteor.kcode.createAndroidProfileHost
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
import android.app.Activity
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cordis.plugin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidProfileEarlyStartupTest {
    @Test(timeout = 120_000)
    fun blockedPackageDirectoryRetainsManagementAndCanRecover(): Unit = runBlocking {
        blockedDirectoryRecovery("cordis_plugins", includeDefaults = false)
    }

    @Test(timeout = 120_000)
    fun bundledStagingFailureRetainsManagementAndCanRecover(): Unit = runBlocking {
        blockedDirectoryRecovery("cordis_plugins/bundled-imports", includeDefaults = true)
    }

    private suspend fun blockedDirectoryRecovery(relative: String, includeDefaults: Boolean) {
        val directory = newDirectory()
        val repository = FileProfileRepository(File(directory, "cordis_profiles"))
        repository.saveDraft(ProfileDefinition(id = "saved"))
        val before = repository.state()
        val blocked = File(directory, relative).toPath()
        Files.createDirectories(blocked.parent)
        blocked.toFile().writeText("retained obstruction")
        val host = withContext(Dispatchers.Main.immediate) {
            createAndroidProfileHost(activity(directory), profileId = "saved",
                profile = KcodePluginProfile(includeDefaults = includeDefaults))
        }
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertNotNull(host.state.value.failure)
            assertEquals(before, repository.state())
            assertTrue(host.profileTemplates.isEmpty())
            assertNotNull(host.profileTemplateFailure)
            val client = assertNotNull(host.profileCommands)
            assertEquals(listOf("saved"), client.catalogue().profiles.map { it.id })
            val saved = client.writeDraft(ProfileDraftWrite(ProfileDefinition(id = "repair"), before.revision, createOnly = true))
            val request = ProfileActivationRequest(ProfileTarget("repair", ProfileSource.Draft), saved.revision)
            val beforeRetry = repository.state()
            assertTrue(client.preview(request.target).diagnostics.isNotEmpty())
            assertEquals(ProfileCommandPhase.Failed, client.submit(ProfileCommand.Activate(request)).await().phase)
            assertEquals(beforeRetry, repository.state())
            assertEquals("retained obstruction", blocked.toFile().readText())
            Files.delete(blocked)
            assertTrue(client.preview(request.target).packagesVerified)
            assertEquals(beforeRetry, repository.state())
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertEquals(1, host.profileTemplates.size)
            assertEquals(null, host.profileTemplateFailure)
            assertEquals(ProfileCommandPhase.Succeeded, client.submit(ProfileCommand.Activate(request)).await().phase)
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertEquals("repair", repository.selected())
            assertEquals(ProfileDefinition(id = "saved"), repository.loadDraft("saved"))
        } finally { host.close(); directory.deleteRecursively() }
    }

    @Test(timeout = 120_000)
    fun unavailableRepositoryDirectoryCanBeRetriedWithoutRecreatingHost(): Unit = runBlocking {
        val directory = newDirectory()
        val blocked = File(directory, "cordis_profiles").toPath()
        blocked.toFile().writeText("retained repository obstruction")
        val host = withContext(Dispatchers.Main.immediate) {
            createAndroidProfileHost(activity(directory), profileId = "repair",
                profile = KcodePluginProfile(includeDefaults = false))
        }
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            val client = assertNotNull(host.profileCommands)
            assertFailsWith<IOException> { client.catalogue() }
            assertEquals("retained repository obstruction", blocked.toFile().readText())
            Files.delete(blocked)
            val catalogue = client.catalogue()
            assertTrue(catalogue.profiles.isEmpty())
            val saved = client.writeDraft(ProfileDraftWrite(ProfileDefinition(id = "repair"), catalogue.revision, createOnly = true))
            assertEquals(ProfileCommandPhase.Succeeded, client.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("repair", ProfileSource.Draft), saved.revision,
            ))).await().phase)
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
        } finally { host.close(); directory.deleteRecursively() }
    }

    @Test(timeout = 120_000)
    fun failedModuleFactoryRetriesWithoutAllocatingUntilActivation(): Unit = runBlocking {
        val directory = newDirectory()
        val repository = FileProfileRepository(File(directory, "cordis_profiles"))
        repository.saveDraft(ProfileDefinition(id = "saved", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("instance", "example.module"))),
        )))
        val before = repository.state()
        var healthy = false
        var allocations = 0
        val host = withContext(Dispatchers.Main.immediate) {
            createAndroidProfileHost(activity(directory), profileId = "saved",
                profile = KcodePluginProfile(includeDefaults = false), moduleFactories = mapOf("example.module" to {
                    check(healthy) { "Module catalogue unavailable" }
                    kcodePlugin(PluginDescriptor("example.module", "test", "test", emptySet()), plugin<Unit> { _, _ ->
                        allocations++
                    }, Unit)
                }))
        }
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertEquals("Module catalogue unavailable", host.state.value.failure?.message)
            val client = assertNotNull(host.profileCommands)
            val target = ProfileTarget("saved", ProfileSource.Draft)
            assertEquals(before.revision, client.catalogue().revision)
            assertTrue(client.preview(target).diagnostics.isNotEmpty())
            assertEquals(0, allocations)
            assertEquals(before, repository.state())
            healthy = true
            assertTrue(client.preview(target).packagesVerified)
            assertEquals(0, allocations)
            assertEquals(before, repository.state())
            assertEquals(ProfileCommandPhase.Succeeded, client.submit(ProfileCommand.Activate(
                ProfileActivationRequest(target, before.revision),
            )).await().phase)
            assertEquals(1, allocations)
            assertEquals("saved", repository.selected())
        } finally { host.close(); directory.deleteRecursively() }
    }

    private fun newDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "early-profile-${System.nanoTime()}").apply { check(mkdirs()) }
    }

    private fun activity(directory: File): Activity {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
        }
        return object : Activity() {
            init { attachBaseContext(isolated) }
            override fun getApplicationContext(): android.content.Context = isolated
            override fun getFilesDir() = directory
            override fun getAssets() = context.assets
            override fun getResources() = context.resources
        }
    }
}
