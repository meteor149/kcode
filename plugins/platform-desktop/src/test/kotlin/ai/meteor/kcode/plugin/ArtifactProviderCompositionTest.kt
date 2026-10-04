package ai.meteor.kcode.plugin

import ai.meteor.kcode.artifact.ArtifactFileStore
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin

class ArtifactProviderCompositionTest {
    @Test
    fun fileProviderIsOwnedByItsPluginAndHostProjectionRebinds() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var slow = true
        val store = object : ArtifactFileStore {
            override suspend fun exists(path: String) = false
            override suspend fun readText(path: String): String? {
                if (slow) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                    }
                }
                return null
            }
        }
        lateinit var current: ArtifactRepository
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            artifactFileStore = store,
            featurePlugins = listOf(kcodePlugin(PluginDescriptor("test.artifacts", "builtin", "test", emptySet()), plugin<Unit>(
                name = "capture-artifacts", inject = dependencies(KcodeArtifacts.Key),
            ) { ctx, _ -> current = ctx.require(KcodeArtifacts.Key).repository }, Unit)),
        ))
        val old = current
        try {
            assertFalse(old is MutableArtifactRepository)
            assertTrue(old.javaClass.name.startsWith("ai.meteor.kcode.plugin.artifacts."))
            val reading = backgroundScope.async { runtime.artifactRepository.list() }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled("provider.artifacts.platform", false) }
            cleaning.await()
            assertFalse(disabling.isCompleted)
            release.complete(Unit)
            disabling.await()
            reading.join()
            assertTrue(reading.isCancelled)
            assertFailsWith<IllegalStateException> { old.list() }
            assertFails { runtime.artifactRepository.list() }
            slow = false
            runtime.pluginManager.setEnabled("provider.artifacts.platform", true)
            assertNotSame(old, current)
            assertEquals(emptyList(), runtime.artifactRepository.list())
            assertFailsWith<IllegalStateException> { old.list() }
        } finally {
            release.complete(Unit)
            runtime.close()
        }
        assertFailsWith<IllegalStateException> { runtime.artifactRepository.list() }
    }
}
