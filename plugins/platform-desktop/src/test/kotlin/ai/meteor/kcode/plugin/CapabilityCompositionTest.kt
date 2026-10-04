@file:OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)

package ai.meteor.kcode.plugin

import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.meteor.kcode.plugin.shell.AgentShellTool
import ai.meteor.kcode.AgentToolContext
import ai.meteor.kcode.plugin.nativefilesystem.DesktopAgentWorkspaceFileSystem
import ai.meteor.kcode.MultiAgentCoordinator
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeFileSystem
import ai.meteor.kcode.plugin.api.KcodeTools
import ai.meteor.kcode.plugin.api.KcodeWebSearch
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellResult
import ai.meteor.kcode.plugin.feature.desktopShellToolPlugin
import ai.meteor.kcode.plugin.feature.filesystemToolPlugin
import ai.meteor.kcode.plugin.feature.webSearchToolPlugin
import ai.meteor.kcode.plugin.provider.filesystemProviderPlugin
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.tools.search.WebSearchBackend
import ai.meteor.kcode.tools.search.WebSearchResponse
import ai.meteor.kcode.tools.search.WebSearchResult
import ai.meteor.kcode.plugin.websearch.WebSearchTool
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import ai.meteor.kcode.plugin.api.FileSystemBackend
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.api.ShellRequest
import ai.meteor.kcode.plugin.provider.shellProviderPlugin
import ai.meteor.kcode.plugin.provider.ubuntuShellProviderPlugin
import ai.meteor.kcode.AgentShellExecutor
import ai.koog.rag.base.files.FileSystemProvider
import java.nio.file.Path
import org.cordis.dependencies
import org.cordis.plugin

class CapabilityCompositionTest {
    @Test
    fun subagentProviderDisposalWaitsForChildrenAndReEnablingCreatesANewFactory() = runTest {
        lateinit var factory: ai.meteor.kcode.SubagentCoordinatorFactory
        val capture = kcodePlugin(descriptor("test.subagent-owner"),
            plugin<Unit>(name = "subagent-owner", inject = dependencies(ai.meteor.kcode.plugin.api.KcodeSubagents.Key)) { ctx, _ ->
                factory = ctx.require(ai.meteor.kcode.plugin.api.KcodeSubagents.Key).factory
            }, Unit)
        val runtime = KcodePluginRuntime.create(config(listOf(capture)))
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val staleFactory = factory
            val coordinator = factory.create(backgroundScope, "root", {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }, {})
            coordinator.spawn("/root", "worker", "task", null)
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled("provider.subagents.in-process", false) }
            cleaning.await()
            assertFalse(disabling.isCompleted)
            assertFailsWith<IllegalStateException> { staleFactory.create(backgroundScope, "old", { "unused" }, {}) }
            assertFailsWith<IllegalStateException> { coordinator.list("/root", null) }
            release.complete(Unit)
            disabling.await()
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.agent-loop.koog" }.state)
            runtime.pluginManager.setEnabled("provider.subagents.in-process", true)
            assertFalse(staleFactory === factory)
            val replacement = factory.create(backgroundScope, "new", { "answer" }, {})
            assertContains(replacement.list("/root", null), "No matching agents")
            replacement.shutdown()
        } finally { release.complete(Unit); runtime.close() }
    }

    @Test
    fun filesystemProviderWaitsForIoCleanupAndRejectsAllStaleEntryPoints() = runTest {
        val directory = Files.createTempDirectory("kcode-fs-owner")
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var backend: FileSystemBackend
        val delegate = object : FileSystemProvider.ReadWrite<Path> by DesktopAgentWorkspaceFileSystem(directory) {
            override suspend fun readBytes(path: Path): ByteArray {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val capture = kcodePlugin(descriptor("test.fs-owner"),
            plugin<Unit>(name = "fs-owner", inject = dependencies(KcodeFileSystem.Key)) { ctx, _ ->
                backend = ctx.require(KcodeFileSystem.Key).backend
            }, Unit)
        val runtime = KcodePluginRuntime.create(config(listOf(filesystemProviderPlugin(delegate), capture)))
        try {
            val stale = backend
            val read = async { stale.readBytes("/workspace/file") }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled("provider.fs.platform", false) }
            cleaning.await()
            assertFalse(disabling.isCompleted)
            assertFailsWith<IllegalStateException> { stale.normalize("/workspace/file") }
            assertFailsWith<IllegalStateException> { stale.exists("/workspace/file") }
            assertFailsWith<IllegalStateException> { stale.inputStream("/workspace/file") }
            assertFailsWith<IllegalStateException> { stale.outputStream("/workspace/file") }
            release.complete(Unit)
            disabling.await()
            read.join()
            assertTrue(read.isCancelled)
            runtime.pluginManager.setEnabled("provider.fs.platform", true)
            assertFalse(stale === backend)
            assertEquals("/workspace/file", backend.normalize("/workspace/file"))
        } finally { release.complete(Unit); runtime.close(); Files.delete(directory) }
    }

    @Test
    fun systemAndUbuntuShellProvidersWaitForProcessCleanupAndInvalidateOldExecutors() = runTest {
        for (ubuntu in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            lateinit var backend: ShellBackend
            val executor = object : AgentShellExecutor {
                override suspend fun execute(command: String, workingDirectory: String?): AgentShellExecutor.ExecutionResult {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                    }
                }
            }
            val provider = if (ubuntu) ubuntuShellProviderPlugin(executor) else shellProviderPlugin(executor)
            val capture = kcodePlugin(descriptor("test.shell-owner"),
                plugin<Unit>(name = "shell-owner", inject = if (ubuntu) dependencies(KcodeUbuntuShell.Key) else dependencies(KcodeShell.Key)) { ctx, _ ->
                    backend = if (ubuntu) ctx.require(KcodeUbuntuShell.Key).executor else ctx.require(KcodeShell.Key).executor
                }, Unit)
            val runtime = KcodePluginRuntime.create(config(listOf(provider, capture)))
            try {
                val stale = backend
                val running = async { stale.run(ShellRequest("test")) }
                entered.await()
                val disabling = async { runtime.pluginManager.setEnabled(provider.descriptor.id, false) }
                cleaning.await()
                assertFalse(disabling.isCompleted)
                assertFailsWith<IllegalStateException> { stale.run(ShellRequest("old")) }
                release.complete(Unit)
                disabling.await()
                running.join()
                assertTrue(running.isCancelled)
                runtime.pluginManager.setEnabled(provider.descriptor.id, true)
                assertFalse(stale === backend)
            } finally { release.complete(Unit); runtime.close() }
        }
    }

    @Test
    fun filesystemToolsFollowProviderAndRetainWorkspaceContainment() = runTest {
        val first = Files.createTempDirectory("kcode-fs-first")
        val second = Files.createTempDirectory("kcode-fs-second")
        Files.writeString(first.resolve("test.txt"), "first")
        Files.writeString(second.resolve("test.txt"), "second")
        lateinit var tools: KcodeTools
        lateinit var publishedBackend: FileSystemBackend
        val capture = captureTools { tools = it }
        val captureCapabilities = kcodePlugin(
            PluginDescriptor("test.fs-capabilities", "test", "test", emptySet()),
            plugin<Unit>(name = "fs-capabilities", inject = dependencies(KcodeFileSystem.Key)) { ctx, _ ->
                publishedBackend = ctx.require(KcodeFileSystem.Key).backend
                assertNull(ctx.require(KcodeFileSystem.Key).harness)
                assertNull(ctx.require(KcodeFileSystem.Key).observations)
            },
            Unit,
        )
        val runtime = KcodePluginRuntime.create(config(listOf(
            filesystemProviderPlugin(DesktopAgentWorkspaceFileSystem(first)), filesystemToolPlugin(), capture, captureCapabilities,
        )))
        val turn = AgentToolContext("/root", MultiAgentCoordinator(backgroundScope, "fixture", runAgent = { "done" }), null, null, null)
        suspend fun read(): String {
            val reader = tools.snapshot(turn).getTool("__read_file__")
            val result = reader.executeUnsafe(ReadFileTool.Args("/workspace/test.txt"))
            return reader.encodeResultToStringUnsafe(result, ai.koog.serialization.kotlinx.KotlinxSerializer())
        }
        try {
            assertContains(read(), "first")
            val stale = publishedBackend
            runtime.replacePlugin(filesystemProviderPlugin(DesktopAgentWorkspaceFileSystem(second)))
            assertFailsWith<IllegalStateException> { stale.readBytes("/workspace/test.txt") }
            assertContains(read(), "second")
            val reader = tools.snapshot(turn).getTool("__read_file__")
            assertFailsWith<IllegalArgumentException> { reader.executeUnsafe(ReadFileTool.Args("/workspace/../outside")) }
            runtime.pluginManager.setEnabled("provider.fs.platform", false)
            assertFalse("consumer.tools.filesystem" in runtime.diagnostics().toolContributions)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "consumer.tools.filesystem" }.state)
            runtime.pluginManager.setEnabled("provider.fs.platform", true)
            assertContains(read(), "second")
        } finally {
            runtime.close()
            Files.deleteIfExists(first.resolve("test.txt"))
            Files.deleteIfExists(second.resolve("test.txt"))
            Files.delete(first)
            Files.delete(second)
        }
    }

    @Test
    fun shellAndSearchConsumersRebindAndFailedProviderRestoresTools() = runTest {
        lateinit var tools: KcodeTools
        val runtime = KcodePluginRuntime.create(config(listOf(
            shellMount("first"), searchMount("first"), desktopShellToolPlugin(), webSearchToolPlugin(), captureTools { tools = it },
        )))
        val turn = AgentToolContext("/root", MultiAgentCoordinator(backgroundScope, "fixture", runAgent = { "done" }), null, null, null)
        suspend fun shell() = tools.snapshot(turn).getTool("execute_shell_command").executeUnsafe(AgentShellTool.Args("test")) as String
        suspend fun search() = tools.snapshot(turn).getTool("web_search").executeUnsafe(WebSearchTool.Args("test")) as String
        try {
            assertContains(shell(), "first")
            assertContains(search(), "first")
            runtime.replacePlugin(shellMount("second"))
            runtime.replacePlugin(searchMount("second"))
            assertContains(shell(), "second")
            assertContains(search(), "second")
            val failure = kcodePlugin(descriptor("provider.shell.platform"), plugin<Unit>(name = "failed-shell") { _, _ -> error("rejected") }, Unit)
            assertFailsWith<IllegalStateException> { runtime.replacePlugin(failure) }
            assertContains(shell(), "second")
            runtime.pluginManager.setEnabled("provider.web.search-http", false)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "consumer.tools.web-search" }.state)
            assertFalse("consumer.tools.web-search" in runtime.diagnostics().toolContributions)
            runtime.pluginManager.setEnabled("provider.web.search-http", true)
            assertContains(search(), "second")
            assertTrue("consumer.tools.shell" in runtime.diagnostics().toolContributions)
        } finally {
            runtime.close()
        }
    }
}

private fun config(features: List<KcodePluginMount>) = KcodePluginRuntimeConfig(
    InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
    featurePlugins = features,
)

private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())

private fun captureTools(capture: (KcodeTools) -> Unit) = kcodePlugin(
    descriptor("test.capture-tools"),
    plugin<Unit>(name = "capture-tools", inject = dependencies(KcodeTools.Key)) { ctx, _ -> capture(ctx.require(KcodeTools.Key)) },
    Unit,
)

private fun shellMount(answer: String) = kcodePlugin(
    descriptor("provider.shell.platform"),
    plugin<Unit>(name = "shell-$answer") { ctx, _ -> KcodeShell(ctx, ShellBackend { ShellResult(answer, 0) }) },
    Unit,
)

private fun searchMount(answer: String) = kcodePlugin(
    descriptor("provider.web.search-http"),
    plugin<Unit>(name = "search-$answer") { ctx, _ ->
        KcodeWebSearch(ctx, object : WebSearchBackend {
            override suspend fun search(query: String, maxResults: Int) = WebSearchResponse(answer, listOf(WebSearchResult(answer, "https://example.com", answer)))
        })
    },
    Unit,
)
