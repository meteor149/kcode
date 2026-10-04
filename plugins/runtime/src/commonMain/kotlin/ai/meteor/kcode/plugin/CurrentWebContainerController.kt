package ai.meteor.kcode.plugin

import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebPreviewRequest

/** Resolve once per call; the selected provider's facade owns the complete operation. */
internal class CurrentWebContainerController(
    private val resolve: suspend () -> WebContainerController,
) : WebContainerController {
    override suspend fun launch(request: WebPreviewRequest) = resolve().launch(request)
    override suspend fun list() = resolve().list()
    override suspend fun screenshot(containerId: String) = resolve().screenshot(containerId)
    override suspend fun inspect(containerId: String) = resolve().inspect(containerId)
    override suspend fun interact(request: WebInteractionRequest) = resolve().interact(request)
    override suspend fun console(containerId: String, cursor: Long, limit: Int) = resolve().console(containerId, cursor, limit)
    override suspend fun setState(containerId: String, state: WebContainerState) = resolve().setState(containerId, state)
    override suspend fun close(containerId: String) = resolve().close(containerId)
    override suspend fun closeAll() = resolve().closeAll()
}
