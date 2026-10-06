package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopKoogChatRuntime
import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.AgentRuntimeOwner
import ai.meteor.kcode.KcodeAgentRuntime
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import ai.meteor.kcode.plugin.profiles.ProfileManagement
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeProfileStartupRecoveryTest {
    @Test
    fun unavailableTemplateDoesNotWithdrawStartupRecoveryMetadata(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-template-metadata")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        repository.saveDraft(definition("broken"))
        val commands = ProfileCommandGateway()
        val host = KcodeProfileHost.start({ "broken" }, ProfileRuntimeFactory { error("unused preparation") },
            ProfileManagement(repository, { emptyList() }, { error("unused preparation") }), commands,
            templates = { error("template unavailable") }) { error("initial preparation failed") }
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertTrue(host.profileTemplates.isEmpty())
            assertEquals("template unavailable", host.profileTemplateFailure?.message)
            assertEquals(listOf("broken"), commands.catalogue().profiles.map { it.id })
        } finally { host.close(); home.toFile().deleteRecursively() }
    }

    @Test
    fun nativeTemplateCreatesASeparateRepairWithoutReplacingFailedIntent(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-template-recovery")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        val broken = definition("broken", "missing.module")
        repository.saveDraft(broken)
        val host = createDesktopProfileHost(homeDirectory = home, profileId = "broken")
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            val template = host.profileTemplates.single()
            assertEquals(setOf("kcode.base", "kcode.agent", "kcode.default-ui"), template.bundles.map { it.id }.toSet())
            val repair = template.copy(id = "repair", displayName = "Repair", dataScope = ProfileDataScope(workspace = "profile"))
            val client = assertNotNull(host.profileCommands)
            val catalogue = client.catalogue()
            val saved = client.writeDraft(ProfileDraftWrite(repair, catalogue.revision, createOnly = true))
            assertEquals(broken, repository.loadDraft("broken"))
            assertEquals(null, repository.selected())
            val result = client.submit(ProfileCommand.Activate(ProfileActivationRequest(
                ProfileTarget("repair", ProfileSource.Draft), saved.revision,
            ))).await()
            assertEquals(ProfileCommandPhase.Succeeded, result.phase)
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertEquals(repair, repository.loadCommitted("repair")!!.definition)
            assertEquals(broken, repository.loadDraft("broken"))
            assertEquals("repair", repository.selected())
        } finally { host.close(); home.toFile().deleteRecursively() }
    }

    @Test
    fun cancellationAfterInitialCreationClosesOwnerAndUnboundGateway(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-startup-publication-cancellation")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        val commands = ProfileCommandGateway()
        var retirements = 0
        try {
            val creation = async {
                KcodeProfileHost.start({ "test" }, ProfileRuntimeFactory { error("unused preparation") },
                    ProfileManagement(repository, { emptyList() }, { error("unused preparation") }), commands) {
                    currentCoroutineContext()[Job]!!.cancel()
                    KcodeAgentRuntime(object : ChatService {
                        override suspend fun reply(configuration: ModelConfiguration, history: List<ChatMessage>, prompt: String) = "unused"
                    }, owner = AgentRuntimeOwner { retirements++ })
                }
            }
            creation.join()
            assertTrue(creation.isCancelled)
            assertEquals(1, retirements)
            assertEquals(ProfileManagementPhase.Closed, commands.state.value.phase)
        } finally { commands.close(); home.toFile().deleteRecursively() }
    }

    private fun definition(id: String, module: String? = null) = ProfileDefinition(
        id = id,
        patches = if (module == null) emptyList() else listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("instance", module))),
        ),
    )

    @Test
    fun failedInitialProfileRetainsManagementAndCanActivateRepairedDraft(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-startup-recovery")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        repository.saveDraft(definition("broken", "missing.module"))
        val before = repository.state()
        var allocations = 0
        val modules = mapOf("example.module" to {
            kcodePlugin(PluginDescriptor("example.module", "test", "test", emptySet()), plugin<Unit> { _, _ ->
                allocations++
            }, Unit)
        })
        val profile = KcodePluginProfile(includeDefaults = false)
        val host = createDesktopProfileHost(homeDirectory = home, profileId = "broken", profile = profile, moduleFactories = modules)
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertEquals("broken", host.state.value.profileId)
            assertEquals(before, repository.state())
            assertEquals(0, allocations)
            assertFailsWith<IllegalStateException> { host.pluginManager.installed() }
            assertFailsWith<IllegalStateException> {
                createDesktopKoogChatRuntime(homeDirectory = home, profileId = "broken", profile = profile, moduleFactories = modules)
            }
            assertEquals(before, repository.state())
            val catalogue = host.pluginManager.profileCatalogue()
            assertEquals(null, catalogue.activeProfileId)
            val saved = host.pluginManager.writeProfileDraft(ProfileDraftWrite(
                definition("broken", "example.module"), catalogue.revision,
            ))
            val handle = assertNotNull(host.profileCommands).submit(ProfileCommand.Activate(
                ProfileActivationRequest(ProfileTarget("broken", ProfileSource.Draft), saved.revision),
            ))
            assertEquals(ProfileCommandPhase.Succeeded, handle.await().phase)
            assertEquals(ProfileHostState("broken"), host.state.value)
            assertEquals(1, allocations)
            assertEquals("broken", repository.selected())
            assertEquals(1L, host.pluginManager.currentProfile()!!.generation)
        } finally { host.close(); home.toFile().deleteRecursively() }
    }

    @Test
    fun failedPartialStartupRetirementBlocksRecoveryAllocation(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-startup-retirement")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        repository.saveDraft(definition("broken", "example.module"))
        repository.saveDraft(definition("target"))
        val before = repository.state()
        var allocations = 0
        var releases = 0
        val modules = mapOf("example.module" to {
            kcodePlugin(PluginDescriptor("example.module", "test", "test", emptySet()), plugin<Unit> { _, _ ->
                allocations++
                collect { releases++; error("startup cleanup refused") }
                error("startup allocation refused")
            }, Unit)
        })
        val host = createDesktopProfileHost(homeDirectory = home, profileId = "broken",
            profile = KcodePluginProfile(includeDefaults = false), moduleFactories = modules)
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertEquals(1, allocations)
            assertEquals(1, releases)
            assertFailsWith<IllegalStateException> { host.recoverTo("target") }
            assertEquals(before, repository.state())
            assertEquals(1, allocations)
            assertEquals(1, releases)
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertFailsWith<IllegalStateException> { host.close() }
        } finally {
            try { host.close() } catch (_: IllegalStateException) {
                // The startup owner retains the terminal retirement failure.
            } finally { home.toFile().deleteRecursively() }
        }
    }

    @Test
    fun cancelledInitialAllocationIsJoinedAndNotConvertedToRecoveryHost(): Unit = runBlocking {
        val home = Files.createTempDirectory("kcode-startup-cancellation")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        repository.saveDraft(definition("cancelled", "example.module"))
        val before = repository.state()
        val entered = CompletableDeferred<Unit>()
        var releases = 0
        try {
            val creation = async {
                createDesktopProfileHost(homeDirectory = home, profileId = "cancelled",
                    profile = KcodePluginProfile(includeDefaults = false), moduleFactories = mapOf("example.module" to {
                        kcodePlugin(PluginDescriptor("example.module", "test", "test", emptySet()), plugin<Unit> { _, _ ->
                            collect { releases++ }
                            entered.complete(Unit)
                            awaitCancellation()
                        }, Unit)
                    }))
            }
            entered.await()
            creation.cancelAndJoin()
            assertTrue(creation.isCancelled)
            assertEquals(1, releases)
            assertEquals(before, repository.state())
        } finally { home.toFile().deleteRecursively() }
    }
}
