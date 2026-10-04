package ai.meteor.kcode.plugin.pages.web

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

internal class WebContainersUiActions(private val controller: WebContainerController) {
    private val owner = PluginOperationOwner("Web containers UI")
    private val live = MutableStateFlow(true)
    val isAvailable: Boolean get() = live.value

    suspend fun backgroundContainers(): List<WebContainerInfo> = owner.run {
        controller.list().filter { it.state == WebContainerState.Background }
    }

    suspend fun monitor(onChanged: (List<WebContainerInfo>) -> Unit) = owner.run {
        while (currentCoroutineContext().isActive) {
            try {
                val containers = backgroundContainers()
                currentCoroutineContext().ensureActive()
                onChanged(containers)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Retry transient controller failures without replacing the last projection.
            }
            delay(400)
        }
    }

    suspend fun restore(id: String) = owner.run { controller.setState(id, WebContainerState.Foreground) }
    suspend fun closeContainer(id: String) = owner.run { controller.close(id) }

    suspend fun close() {
        live.value = false
        owner.close()
    }
}
