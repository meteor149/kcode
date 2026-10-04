package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeWebContainers
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerScreenshot
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebConsoleSnapshot
import ai.meteor.kcode.webcontainer.WebInteractionAction
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebInteractionResult
import ai.meteor.kcode.webcontainer.WebPageInspection
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import ai.meteor.kcode.webcontainer.WebPreviewResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin

class WebHostProjectionTest {
    @Test
    fun hostProjectionWithdrawsEveryOperationAndFollowsTheReplacement() = runTest {
        val first = FixtureWebController("first")
        lateinit var current: WebContainerController
        val capture = kcodePlugin(PluginDescriptor("test.web", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-web", inject = dependencies(KcodeWebContainers.Key),
        ) { ctx, _ -> current = requireNotNull(ctx.require(KcodeWebContainers.Key).controller) }, Unit)
        val runtime = KcodePluginRuntime.create(config(first).copy(featurePlugins = listOf(capture)))
        val host = runtime.webContainerController
        val old = current
        try {
            assertEquals("first", host.launch(WebPreviewRequest("/workspace/index.html", "preview")).containerId)
            runtime.pluginManager.setEnabled("provider.web-containers.platform", false)
            val callsAfterDisposal = first.calls
            everyOperation(host).forEach { call -> assertFails { call() } }
            assertEquals(callsAfterDisposal, first.calls)
            assertFailsWith<IllegalStateException> { old.list() }
            runtime.pluginManager.setEnabled("provider.web-containers.platform", true)
            assertTrue(current !== old)
            assertEquals("first", host.list().single().id)
            val second = FixtureWebController("second")
            runtime.replacePlugin(kcodePlugin(
                PluginDescriptor("provider.web-containers.platform", "test", "test", setOf("webContainers")),
                WebContainersProviderPlugin, second,
            ))
            assertSame(host, runtime.webContainerController)
            assertEquals("second", host.launch(WebPreviewRequest("/workspace/index.html", "preview")).containerId)
            assertEquals("second", host.list().single().id)
            assertEquals("id", host.screenshot("id").containerId)
            assertEquals("second", host.inspect("id").title)
            assertEquals("second", host.interact(WebInteractionRequest("id", WebInteractionAction.Reload)).target)
            assertEquals(12L, host.console("id", 12, 3).nextCursor)
            assertEquals(WebContainerState.Background, host.setState("id", WebContainerState.Background).state)
            host.close("id")
            host.closeAll()
            assertEquals(9, second.calls)
        } finally { runtime.close() }
        everyOperation(host).forEach { call -> assertFailsWith<IllegalStateException> { call() } }
    }

    @Test
    fun hostLaunchBelongsToTheProviderAndWithdrawalWaitsForItsCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var resourcesClosed = false
        val backend = object : FixtureWebController("waiting") {
            override suspend fun launch(request: WebPreviewRequest): WebPreviewResult {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
            override suspend fun closeAll() {
                resourcesClosed = true
            }
        }
        val runtime = KcodePluginRuntime.create(config(backend))
        try {
            val running = backgroundScope.async { runtime.webContainerController.launch(WebPreviewRequest("/workspace/index.html", "preview")) }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled("provider.web-containers.platform", false) }
            cleaning.await()
            assertFalse(disabling.isCompleted)
            assertFalse(resourcesClosed)
            release.complete(Unit)
            disabling.await()
            running.join()
            assertTrue(resourcesClosed)
            assertTrue(running.isCancelled)
        } finally { release.complete(Unit); runtime.close() }
    }

    @Test
    fun absentControllerDoesNotCreateAHostCapability() = runTest {
        val runtime = KcodePluginRuntime.create(config(null))
        try {
            everyOperation(runtime.webContainerController).forEach { call -> assertFails { call() } }
        } finally { runtime.close() }
    }
}

private fun config(controller: WebContainerController?) = KcodePluginRuntimeConfig(
    interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
    webContainerController = controller,
)

private fun everyOperation(controller: WebContainerController): List<suspend () -> Unit> = listOf(
    { controller.launch(WebPreviewRequest("/workspace/index.html", "preview")); Unit },
    { controller.list(); Unit },
    { controller.screenshot("id"); Unit },
    { controller.inspect("id"); Unit },
    { controller.interact(WebInteractionRequest("id", WebInteractionAction.Reload)); Unit },
    { controller.console("id", 12, 3); Unit },
    { controller.setState("id", WebContainerState.Background); Unit },
    { controller.close("id") },
    { controller.closeAll() },
)

private open class FixtureWebController(private val generation: String) : WebContainerController {
    var calls = 0
    override suspend fun launch(request: WebPreviewRequest): WebPreviewResult {
        calls += 1
        return WebPreviewResult(generation, request.entryPath, 0, request.title)
    }
    override suspend fun list(): List<WebContainerInfo> {
        calls += 1
        return listOf(WebContainerInfo(generation, "/workspace/index.html", generation, "test", WebContainerState.Foreground))
    }
    override suspend fun screenshot(containerId: String): WebContainerScreenshot {
        calls += 1
        return WebContainerScreenshot(containerId, byteArrayOf(1), 1, 1)
    }
    override suspend fun inspect(containerId: String): WebPageInspection {
        calls += 1
        return WebPageInspection(containerId, "/workspace/index.html", generation, 1, 1, emptyList())
    }
    override suspend fun interact(request: WebInteractionRequest): WebInteractionResult {
        calls += 1
        return WebInteractionResult(request.containerId, request.action, generation)
    }
    override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot {
        calls += 1
        return WebConsoleSnapshot(containerId, emptyList(), cursor)
    }
    override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo {
        calls += 1
        return WebContainerInfo(containerId, "/workspace/index.html", generation, "test", state)
    }
    override suspend fun close(containerId: String) { calls += 1 }
    override suspend fun closeAll() { calls += 1 }
}
