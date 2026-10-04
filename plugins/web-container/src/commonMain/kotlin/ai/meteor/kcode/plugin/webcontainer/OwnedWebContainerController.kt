package ai.meteor.kcode.plugin.webcontainer

import ai.meteor.kcode.plugin.api.PluginCleanupException
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ai.meteor.kcode.webcontainer.WebConsoleSnapshot
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerScreenshot
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebInteractionResult
import ai.meteor.kcode.webcontainer.WebPageInspection
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import ai.meteor.kcode.webcontainer.WebPreviewResult

internal class OwnedWebContainerController(
    private val delegate: WebContainerController,
    private val owner: PluginOperationOwner,
) : WebContainerController {
    override suspend fun launch(request: WebPreviewRequest): WebPreviewResult = owner.run { delegate.launch(request) }
    override suspend fun list(): List<WebContainerInfo> = owner.run { delegate.list() }
    override suspend fun screenshot(containerId: String): WebContainerScreenshot = owner.run { delegate.screenshot(containerId) }
    override suspend fun inspect(containerId: String): WebPageInspection = owner.run { delegate.inspect(containerId) }
    override suspend fun interact(request: WebInteractionRequest): WebInteractionResult = owner.run { delegate.interact(request) }
    override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot = owner.run { delegate.console(containerId, cursor, limit) }
    override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo = owner.run { delegate.setState(containerId, state) }
    override suspend fun close(containerId: String) = owner.run { delegate.close(containerId) }
    override suspend fun closeAll() = owner.run { delegate.closeAll() }

    suspend fun dispose() {
        withContext(NonCancellable) {
            val failures = mutableListOf<Throwable>()
            runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
            runCatching { delegate.closeAll() }.exceptionOrNull()?.let(failures::add)
            if (failures.isNotEmpty()) throw PluginCleanupException("Web container provider", failures)
        }
    }
}
