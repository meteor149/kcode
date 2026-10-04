package ai.meteor.kcode.plugin

import ai.meteor.kcode.artifact.ArtifactFileStore
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.plugin.api.ArtifactFileStoreFactory
import ai.meteor.kcode.plugin.api.ArtifactFileStoreResource
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.artifacts.FactoryFileArtifactsProviderPlugin
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin

class ArtifactResourceCompositionTest {
    @Test
    fun disabledProviderAllocatesOnMountAndPreservesReadOnlyCapability() = runTest {
        var opened = 0
        var closed = 0
        lateinit var current: ArtifactRepository
        val runtime = KcodePluginRuntime.create(config(ArtifactFileStoreFactory {
            opened++
            ArtifactFileStoreResource(EmptyFiles()) { closed++ }
        }) { current = it }.copy(profile = KcodePluginProfile(disabled = setOf(ProviderId))))
        try {
            assertEquals(0, opened)
            runtime.pluginManager.setEnabled(ProviderId, true)
            assertEquals(1, opened)
            assertFalse(current is MutableArtifactRepository)
            assertTrue(current.list().isEmpty())
            val previous = current
            runtime.pluginManager.setEnabled(ProviderId, false)
            assertEquals(1, closed)
            assertFailsWith<IllegalStateException> { previous.list() }
            runtime.pluginManager.setEnabled(ProviderId, true)
            assertEquals(2, opened)
            assertTrue(current.list().isEmpty())
        } finally { runtime.close() }
        assertEquals(opened, closed)
    }

    @Test
    fun failedReplacementReallocatesPreviousProvider() = runTest {
        var opened = 0
        var closed = 0
        lateinit var current: ArtifactRepository
        val runtime = KcodePluginRuntime.create(config(ArtifactFileStoreFactory {
            opened++
            ArtifactFileStoreResource(EmptyFiles()) { closed++ }
        }) { current = it })
        try {
            val previous = current
            assertFailsWith<IllegalArgumentException> {
                runtime.replacePlugin(kcodePlugin(descriptor(ProviderId), FactoryFileArtifactsProviderPlugin,
                    ArtifactFileStoreFactory { throw IllegalArgumentException("allocation failed") }))
            }
            assertEquals(2, opened)
            assertEquals(1, closed)
            assertFailsWith<IllegalStateException> { previous.list() }
            assertTrue(current.list().isEmpty())
        } finally { runtime.close() }
        assertEquals(opened, closed)
    }

    @Test
    fun cancelledAssemblyReleasesAllocatedResource() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        val creating = backgroundScope.async {
            KcodePluginRuntime.create(config(ArtifactFileStoreFactory {
                entered.complete(Unit)
                release.await()
                ArtifactFileStoreResource(EmptyFiles()) { closed = true }
            }))
        }
        entered.await()
        creating.cancel()
        release.complete(Unit)
        creating.join()
        assertTrue(creating.isCancelled)
        assertTrue(closed)
    }

    @Test
    fun resourceReleaseWaitsForCancelledCallCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        lateinit var current: ArtifactRepository
        val files = object : EmptyFiles() {
            override suspend fun readText(path: String): String? {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val runtime = KcodePluginRuntime.create(config(ArtifactFileStoreFactory {
            ArtifactFileStoreResource(files) { closed = true }
        }) { current = it })
        try {
            val reading = backgroundScope.async { current.list() }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled(ProviderId, false) }
            cleaning.await()
            assertFalse(closed)
            assertFalse(disabling.isCompleted)
            release.complete(Unit)
            disabling.await()
            reading.join()
            assertTrue(closed)
            assertTrue(reading.isCancelled)
        } finally { release.complete(Unit); runtime.close() }
    }

    private fun config(factory: ArtifactFileStoreFactory, bind: ((ArtifactRepository) -> Unit)? = null) =
        KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            artifactFileStoreFactory = factory,
            featurePlugins = listOfNotNull(bind?.let { capture ->
                kcodePlugin(descriptor("test.artifact-resource"), plugin<Unit>(
                    name = "capture-artifact-resource", inject = dependencies(KcodeArtifacts.Key),
                ) { ctx, _ -> capture(ctx.require(KcodeArtifacts.Key).repository) }, Unit)
            }),
        )

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private open class EmptyFiles : ArtifactFileStore {
        override suspend fun readText(path: String): String? = null
        override suspend fun exists(path: String) = false
    }

    private companion object {
        const val ProviderId = "provider.artifacts.platform"
    }
}
