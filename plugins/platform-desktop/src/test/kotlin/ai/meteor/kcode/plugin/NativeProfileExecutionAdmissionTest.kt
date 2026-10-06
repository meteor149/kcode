package ai.meteor.kcode.plugin

import ai.meteor.kcode.createDesktopProfileHost
import ai.meteor.kcode.plugin.api.ExecutionAdmission
import ai.meteor.kcode.plugin.api.KcodeExecution
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.RootAgentPath
import ai.meteor.kcode.SubagentCoordinatorFactory
import ai.meteor.kcode.plugin.api.KcodeSubagents
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.profiles.FileProfileRepository
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cordis.plugin
import org.cordis.dependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class NativeProfileExecutionAdmissionTest {
    @Test
    fun directPackagedSubagentWorkBlocksSwitchAndCancellationJoinsBeforeTargetPreparation(): Unit = runBlocking {
        val home = Files.createTempDirectory("native-profile-subagent-admission")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        repository.saveDraft(ProfileDefinition(id = "second"))
        lateinit var factory: SubagentCoordinatorFactory
        val capture = kcodePlugin(PluginDescriptor("provider.ui.compose", "test", "test", emptySet()),
            plugin<Unit>(inject = dependencies(KcodeSubagents.Key)) { ctx, _ -> factory = ctx.require(KcodeSubagents.Key).factory }, Unit)
        val host = createDesktopProfileHost(homeDirectory = home, profile = KcodePluginProfile(overrides = listOf(capture)))
        val release = CompletableDeferred<Unit>()
        try {
            host.state.value.failure?.let { throw it }
            val original = factory
            val started = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val coordinator = original.create(CoroutineScope(coroutineContext), "external root", {
                assertFailsWith<IllegalStateException> { host.close() }
                started.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }, {})
            assertNotSame(SubagentCoordinatorFactory::class.java.classLoader, original.javaClass.classLoader)
            coordinator.spawn(RootAgentPath, "worker", "detached task", null)
            withTimeout(5_000) { started.await() }
            val committed = repository.state()
            assertFailsWith<IllegalStateException> { host.switchTo("second") }
            assertEquals(committed, repository.state())
            val switching = async { host.switchTo("second", cancelActive = true) }
            withTimeout(5_000) { cleaning.await() }
            assertFalse(switching.isCompleted)
            assertEquals(ProfileHostPhase.Preparing, host.state.value.phase)
            assertEquals(committed, repository.state())
            assertFailsWith<CancellationException> { coordinator.followupTask(RootAgentPath, "worker", "denied") }
            assertFailsWith<CancellationException> { coordinator.sendMessage(RootAgentPath, "worker", "denied") }
            release.complete(Unit)
            withTimeout(10_000) { switching.await() }
            assertEquals("second", repository.selected())
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertFailsWith<IllegalStateException> { original.create(CoroutineScope(coroutineContext), "stale", { "" }, {}) }
            assertFailsWith<IllegalStateException> { coordinator.spawn(RootAgentPath, "stale", "denied", null) }
        } finally {
            release.complete(Unit)
            try { host.close() } finally { home.toFile().deleteRecursively() }
        }
    }

    @Test
    fun failedProductStartupRetiresCapturedAdmissionWithTheRootEffect(): Unit = runBlocking {
        val home = Files.createTempDirectory("native-profile-startup-admission")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        repository.saveDraft(ProfileDefinition(id = "broken", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("failing", "example.failing"))),
        )))
        lateinit var admission: ExecutionAdmission
        val host = createDesktopProfileHost(homeDirectory = home, profileId = "broken",
            profile = KcodePluginProfile(includeDefaults = false), moduleFactories = mapOf("example.failing" to {
                kcodePlugin(PluginDescriptor("example.failing", "test", "test", emptySet()),
                    plugin<Unit> { ctx, _ ->
                        admission = ctx.root.require(KcodeExecution.Key).admission
                        error("Allocation refused")
                    }, Unit)
            }))
        try {
            assertEquals(ProfileHostPhase.RecoveryRequired, host.state.value.phase)
            assertFailsWith<CancellationException> { admission.run { error("Stale startup reference") } }
        } finally { host.close(); home.toFile().deleteRecursively() }
    }

    @Test
    fun failedCandidateKeepsExecutionClosedAndRestoresPublishedOldGeneration(): Unit = runBlocking {
        val home = Files.createTempDirectory("native-profile-candidate-admission")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        fun definition(id: String, module: String) = ProfileDefinition(id = id, patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("work", module))),
        ))
        repository.saveDraft(definition("first", "example.work"))
        repository.saveDraft(definition("broken", "example.failing"))
        val gates = mutableListOf<ExecutionAdmission>()
        val factories = listOf("example.work", "example.failing").associateWith { module ->
            {
                kcodePlugin(PluginDescriptor(module, "test", "test", emptySet()),
                    plugin<Unit> { ctx, _ ->
                        val gate = ctx.root.require(KcodeExecution.Key).admission
                        gates += gate
                        assertFailsWith<CancellationException> { gate.run { error("Candidate executed") } }
                        if (module == "example.failing") error("Candidate allocation refused")
                    }, Unit)
            }
        }
        val host = createDesktopProfileHost(homeDirectory = home, profileId = "first",
            profile = KcodePluginProfile(includeDefaults = false), moduleFactories = factories)
        try {
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase, host.state.value.failure?.stackTraceToString())
            val original = gates.single()
            original.run { Unit }
            val committed = repository.state()
            assertFailsWith<IllegalStateException> { host.switchTo("broken") }
            assertEquals(committed, repository.state())
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertEquals(3, gates.size)
            assertFailsWith<CancellationException> { original.run { error("Old owner") } }
            assertFailsWith<CancellationException> { gates[1].run { error("Failed candidate") } }
            gates.last().run { Unit }
        } finally { host.close(); home.toFile().deleteRecursively() }
    }

    @Test
    fun directProductWorkBlocksMutationAndSwitchCancellationJoinsCleanup(): Unit = runBlocking {
        val home = Files.createTempDirectory("native-profile-execution-admission")
        val repository = FileProfileRepository(home.resolve("profiles").toFile())
        val definition = ProfileDefinition(id = "first", patches = listOf(
            ProfileOperation.Insert(listOf(ProfileEntry("work", "example.work", isolate = mapOf("executionAdmission" to null)))),
        ))
        repository.saveDraft(definition)
        repository.saveDraft(definition.copy(id = "second"))
        val gates = mutableListOf<ExecutionAdmission>()
        val host = createDesktopProfileHost(homeDirectory = home, profileId = "first",
            profile = KcodePluginProfile(includeDefaults = false), moduleFactories = mapOf("example.work" to {
                kcodePlugin(PluginDescriptor("example.work", "test", "test", emptySet()),
                    plugin<Unit> { ctx, _ ->
                        val gate = ctx.root.require(KcodeExecution.Key).admission
                        gates += gate
                        assertFailsWith<CancellationException> { gate.run { error("Unpublished product work") } }
                    }, Unit)
            }))
        val finishCleanup = CompletableDeferred<Unit>()
        try {
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase, host.state.value.failure?.stackTraceToString())
            val old = gates.single()
            old.run { assertFailsWith<IllegalStateException> { host.close() } }
            val started = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val work = launch {
                old.run {
                    started.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanup.complete(Unit); finishCleanup.await() } }
                }
            }
            started.await()
            val committed = repository.state()
            assertFailsWith<IllegalStateException> { host.runtime.pluginManager!!.setEnabled("work", false) }
            assertFailsWith<IllegalStateException> { host.switchTo("second") }
            assertEquals(committed, repository.state())
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            val switching = async { host.switchTo("second", cancelActive = true) }
            cleanup.await()
            assertFalse(switching.isCompleted)
            assertEquals(ProfileHostPhase.Preparing, host.state.value.phase)
            assertFailsWith<CancellationException> { old.run { error("Must not execute") } }
            finishCleanup.complete(Unit)
            switching.await()
            assertTrue(work.isCompleted)
            assertEquals("second", repository.selected())
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            assertNotSame(old, gates.last())
            assertFailsWith<CancellationException> { old.run { error("Stale admission") } }
            gates.last().run { Unit }
            assertFailsWith<IllegalArgumentException> { host.switchTo("missing") }
            assertEquals(ProfileHostPhase.Ready, host.state.value.phase)
            gates.last().run { Unit }
        } finally {
            finishCleanup.complete(Unit)
            host.close()
            home.toFile().deleteRecursively()
        }
    }
}
