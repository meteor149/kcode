package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin

class RuntimeCloseTest {
    @Test
    fun allCloseCallersWaitForCleanupEvenWhenTheirJobsAreCancelled() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleanups = AtomicInteger()
        lateinit var runtime: KcodePluginRuntime
        runtime = KcodePluginRuntime.create(configuration {
            entered.complete(Unit)
            release.await()
            assertFailsWith<IllegalStateException> { runtime.modelCatalog() }
            assertFailsWith<IllegalStateException> { runtime.diagnostics() }
            cleanups.incrementAndGet()
        })
        val first = async(Dispatchers.Default) { runtime.close() }
        try {
            withTimeout(10_000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { runtime.close() }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            first.cancel()
            second.cancel()
            assertFalse(second.isCompleted)
            release.complete(Unit)
            withTimeout(10_000) { first.join(); second.join() }
            assertEquals(1, cleanups.get())
            withTimeout(10_000) { runtime.close() }
        } finally {
            release.complete(Unit)
            withTimeout(10_000) { first.join(); runtime.close() }
        }
    }

    @Test
    fun concurrentAndRepeatedCloseObserveTheSameFailureAfterRemainingCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val expected = IllegalStateException("external cleanup failed")
        val cleanups = AtomicInteger()
        val config = configuration { cleanups.incrementAndGet() }.copy(
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { _, _, _ ->
                object : DynamicPluginController {
                    override suspend fun install(spec: DynamicPluginSpec) = error("unused")
                    override suspend fun replace(spec: DynamicPluginSpec) = error("unused")
                    override suspend fun uninstall(id: String) = error("unused")
                    override suspend fun installed() = emptyList<DynamicPluginSpec>()
                    override suspend fun setEnabled(id: String, enabled: Boolean) = error("unused")
                    override suspend fun settle() = Unit
                    override suspend fun close() {
                        entered.complete(Unit)
                        release.await()
                        throw expected
                    }
                }
            },
        )
        val runtime = KcodePluginRuntime.create(config)
        val first = async(Dispatchers.Default) { runCatching { runtime.close() } }
        try {
            withTimeout(10_000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { runtime.close() } }
            assertFalse(second.isCompleted)
            release.complete(Unit)
            withTimeout(10_000) {
                assertOriginalFailure(expected, first.await().exceptionOrNull())
                assertOriginalFailure(expected, second.await().exceptionOrNull())
                assertOriginalFailure(expected, runCatching { runtime.close() }.exceptionOrNull())
            }
            assertEquals(1, cleanups.get())
            assertFailsWith<IllegalStateException> { runtime.modelCatalog() }
        } finally {
            release.complete(Unit)
            withTimeout(10_000) { first.await(); runCatching { runtime.close() } }
        }
        Unit
    }

    private fun assertOriginalFailure(expected: Throwable, actual: Throwable?) {
        assertEquals(expected.message, actual?.message)
        // Coroutine stack recovery can copy the exception, retaining the original as its cause.
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }

    private fun configuration(cleanup: suspend () -> Unit) = KcodePluginRuntimeConfig(
        interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
        profile = KcodePluginProfile(includeDefaults = false),
        featurePlugins = listOf(kcodePlugin(
            PluginDescriptor("test.cleanup", "test", "test", emptySet()),
            object : Plugin<Unit> {
                override val name = "test.cleanup"
                override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
                    effect.collect(cleanup)
                }
            },
            Unit,
        )),
    )
}
