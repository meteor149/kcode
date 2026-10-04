package ai.meteor.kcode.plugin

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.test.EmptyHistoryFixture
import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory
import ai.meteor.kcode.plugin.api.HistoryRepositoryResource
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.history.FactoryHistoryProviderPlugin
import ai.meteor.kcode.plugin.history.desktopHistoryRepositoryFactory
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.nio.file.Files
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

class HistoryResourceCompositionTest {
    @Test
    fun disabledHistoryProviderAllocatesNoStorageUntilEnabled() = runTest {
        var opened = 0
        var closed = 0
        val config = config(HistoryRepositoryFactory {
            opened++
            HistoryRepositoryResource(EmptyHistoryFixture()) { closed++ }
        }, emptyList()).copy(profile = KcodePluginProfile(disabled = setOf("provider.history.platform")))
        val runtime = KcodePluginRuntime.create(config)
        try {
            assertEquals(0, opened)
            runtime.pluginManager.setEnabled("provider.history.platform", true)
            assertEquals(1, opened)
            runtime.pluginManager.setEnabled("provider.history.platform", false)
            assertEquals(1, closed)
        } finally { runtime.close() }
    }


    @Test
    fun cancelledAssemblyReleasesTheResourceAllocatedBeforePublication() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        val creating = backgroundScope.async {
            KcodePluginRuntime.create(config(HistoryRepositoryFactory {
                entered.complete(Unit)
                release.await()
                HistoryRepositoryResource(EmptyHistoryFixture()) { closed = true }
            }, emptyList()))
        }
        entered.await()
        creating.cancel()
        release.complete(Unit)
        creating.join()
        assertTrue(creating.isCancelled)
        assertTrue(closed)
    }


    @Test
    fun roomIsClosedOnUnmountAndReopenedWithDurableDataOnRemount() = runTest {
        val directory = Files.createTempDirectory("kcode-history-")
        var opened = 0
        var closed = 0
        lateinit var repository: ConversationHistoryRepository
        val factory = desktopHistoryRepositoryFactory(directory.resolve("history.db"))
        val owned = HistoryRepositoryFactory {
            val resource = factory.create()
            opened++
            HistoryRepositoryResource(resource.repository) { resource.close(); closed++ }
        }
        val capture = kcodePlugin(descriptor("test.history"), plugin<Unit>(
            name = "capture-history", inject = dependencies(KcodeHistory.Key),
        ) { ctx, _ -> repository = ctx.require(KcodeHistory.Key).repository }, Unit)
        val runtime = KcodePluginRuntime.create(config(owned, listOf(capture)))
        try {
            repository.appendMessage(1, "durable", 1, "User", "message")
            val previous = repository
            runtime.pluginManager.setEnabled("provider.history.platform", false)
            assertEquals(1, closed)
            assertFailsWith<IllegalStateException> { previous.loadAll() }
            runtime.pluginManager.setEnabled("provider.history.platform", true)
            assertEquals(2, opened)
            assertEquals("message", repository.loadAll().single().messages.single().content)
            val failedFactory = HistoryRepositoryFactory { throw IllegalArgumentException("allocation failed") }
            assertFailsWith<IllegalArgumentException> {
                runtime.replacePlugin(kcodePlugin(descriptor("provider.history.platform"), FactoryHistoryProviderPlugin, failedFactory))
            }
            assertEquals(2, closed)
            assertEquals(3, opened)
            assertEquals("message", repository.loadAll().single().messages.single().content)
        } finally {
            runtime.close()
            assertEquals(opened, closed)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun nativeResourceClosesOnlyAfterCancelledTransactionCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        lateinit var repository: ConversationHistoryRepository
        val backend = object : ConversationHistoryRepository by EmptyHistoryFixture() {
            override suspend fun appendMessage(conversationId: Long, title: String, messageId: Long, role: String, content: String, isError: Boolean) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val capture = kcodePlugin(descriptor("test.history"), plugin<Unit>(
            name = "capture-history", inject = dependencies(KcodeHistory.Key),
        ) { ctx, _ -> repository = ctx.require(KcodeHistory.Key).repository }, Unit)
        val runtime = KcodePluginRuntime.create(config(HistoryRepositoryFactory {
            HistoryRepositoryResource(backend) { closed = true }
        }, listOf(capture)))
        try {
            val previous = repository
            val writing = backgroundScope.async { previous.appendMessage(1, "t", 1, "User", "message") }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled("provider.history.platform", false) }
            cleaning.await()
            assertFalse(closed)
            assertFalse(disabling.isCompleted)
            release.complete(Unit)
            disabling.await()
            writing.join()
            assertTrue(closed)
            assertTrue(writing.isCancelled)
            assertFailsWith<IllegalStateException> { previous.loadAll() }
        } finally { release.complete(Unit); runtime.close() }
    }

    private fun config(factory: HistoryRepositoryFactory, plugins: List<KcodePluginMount>) = KcodePluginRuntimeConfig(
        interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
        historyRepositoryFactory = factory, featurePlugins = plugins,
    )

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
}
