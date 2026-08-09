@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.meteor.kcode

import ai.koog.agents.core.tools.ToolRegistry
import ai.meteor.kcode.webcontainer.IosWebContainerLauncher
import ai.meteor.kcode.tools.search.WebSearchConfiguration
import ai.meteor.kcode.tools.search.WebSearchProvider
import ai.meteor.kcode.tools.search.WebSearchTool
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolApprovalRequest
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.tools.io.normalizeWorkspacePath
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIViewController

internal class IosToolPermissionState(
    var mode: ToolPermissionMode = ToolPermissionMode.Ask,
)

internal fun createIosAgentWorkspaceRoot(): String {
    val documents = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )?.path,
    )
    return "$documents/kcode/workspace"
}

internal fun createIosKoogChatService(
    settingsStore: AppSettingsStore,
    workspaceRoot: String,
    permissionState: IosToolPermissionState,
    presentingViewController: () -> UIViewController?,
): KoogChatService = createIosKoogChatRuntime(
    settingsStore,
    workspaceRoot,
    permissionState,
    presentingViewController,
).chatService

internal fun createIosKoogChatRuntime(
    settingsStore: AppSettingsStore,
    workspaceRoot: String,
    permissionState: IosToolPermissionState,
    presentingViewController: () -> UIViewController?,
): KcodeAgentRuntime {
    val workspace = IosAgentWorkspace(workspaceRoot)
    val webContainerController = IosWebContainerLauncher(workspaceRoot, presentingViewController)
    return KcodeAgentRuntime(
        chatService = KoogChatService(
            additionalTools = ToolRegistry {
                tool(AgentReadFileTool(workspace))
                tool(AgentListDirectoryTool(workspace))
                tool(AgentWriteFileTool(workspace))
                tool(AgentEditFileTool(workspace))
                webContainerTools(webContainerController)
                tool(WebSearchTool(configurationProvider = {
                    settingsStore.load().let {
                        WebSearchConfiguration(
                            provider = WebSearchProvider.fromCode(it.webSearchProvider),
                            brightDataApiKey = it.webSearchApiKey,
                            exaApiKey = it.exaSearchApiKey,
                        )
                    }
                }))
            },
            toolPermissionModeProvider = { permissionState.mode },
            toolCallApprover = ToolCallApprover { request ->
                confirmIosToolCall(presentingViewController, request)
            },
        ),
        webContainerController = webContainerController,
    )
}

private class IosAgentWorkspace(
    rootPath: String,
) : AgentWorkspace {
    private val root = Path(rootPath)
    private val resolvedRoot: String

    init {
        SystemFileSystem.createDirectories(root)
        resolvedRoot = SystemFileSystem.resolve(root).toString().trimEnd('/')
    }

    override suspend fun readText(path: String): String {
        val target = checked(path, allowRoot = false)
        val metadata = SystemFileSystem.metadataOrNull(target)
        require(metadata?.isRegularFile == true) { "File does not exist: $path" }
        require(metadata.size <= MAX_FILE_BYTES) { "File exceeds the $MAX_FILE_BYTES-byte limit" }
        return SystemFileSystem.source(target).buffered().use { source ->
            source.readByteArray().decodeToString()
        }
    }

    override suspend fun writeText(path: String, content: String) {
        val bytes = content.encodeToByteArray()
        require(bytes.size <= MAX_FILE_BYTES) { "File exceeds the $MAX_FILE_BYTES-byte limit" }
        val target = checked(path, allowRoot = false)
        target.parent?.let { SystemFileSystem.createDirectories(it) }
        val oldSize = SystemFileSystem.metadataOrNull(target)?.size?.coerceAtLeast(0L) ?: 0L
        require(workspaceSize(root) - oldSize + bytes.size <= MAX_WORKSPACE_BYTES) {
            "Workspace size limit exceeded"
        }
        SystemFileSystem.sink(target).buffered().use { sink -> sink.write(bytes) }
    }

    override suspend fun list(path: String): List<AgentWorkspaceEntry> {
        val directory = checked(path, allowRoot = true)
        require(SystemFileSystem.metadataOrNull(directory)?.isDirectory == true) {
            "Directory does not exist: $path"
        }
        return SystemFileSystem.list(directory)
            .map { child ->
                val metadata = requireNotNull(SystemFileSystem.metadataOrNull(child))
                val relative = child.toString().removePrefix(resolvedRoot).trimStart('/')
                AgentWorkspaceEntry(
                    path = "/workspace/$relative",
                    directory = metadata.isDirectory,
                    size = if (metadata.isRegularFile) metadata.size else 0L,
                )
            }
            .sortedBy { it.path }
    }

    private fun checked(virtualPath: String, allowRoot: Boolean): Path {
        val relative = normalizeWorkspacePath(virtualPath, allowRoot)
        val target = if (relative.isEmpty()) root else Path(root, *relative.split('/').toTypedArray())
        var existing: Path? = target
        while (existing != null && !SystemFileSystem.exists(existing)) existing = existing.parent
        val resolvedExisting = SystemFileSystem.resolve(requireNotNull(existing)).toString().trimEnd('/')
        require(resolvedExisting == resolvedRoot || resolvedExisting.startsWith("$resolvedRoot/")) {
            "Path escapes /workspace through a symbolic link"
        }
        return target
    }

    private fun workspaceSize(directory: Path): Long = SystemFileSystem.list(directory).sumOf { child ->
        val metadata = SystemFileSystem.metadataOrNull(child) ?: return@sumOf 0L
        if (metadata.isDirectory) workspaceSize(child) else metadata.size.coerceAtLeast(0L)
    }

    private companion object {
        const val MAX_FILE_BYTES = 1_048_576L
        const val MAX_WORKSPACE_BYTES = 16_777_216L
    }
}

private suspend fun confirmIosToolCall(
    presentingViewController: () -> UIViewController?,
    request: ToolApprovalRequest,
): Boolean = withContext(Dispatchers.Main) {
    val presenter = presentingViewController()?.topPresentedController() ?: return@withContext false
    suspendCancellableCoroutine { continuation ->
        val message = buildString {
            append(request.description.ifBlank { request.name }.take(2_048))
            append("\n\nInput\n")
            append(request.input.take(8_192))
        }
        val alert = UIAlertController.alertControllerWithTitle(
            title = "Allow ${request.name}?",
            message = message,
            preferredStyle = UIAlertControllerStyleAlert,
        )
        alert.addAction(
            UIAlertAction.actionWithTitle("Deny", UIAlertActionStyleCancel) {
                if (continuation.isActive) continuation.resume(false)
            },
        )
        alert.addAction(
            UIAlertAction.actionWithTitle("Allow", UIAlertActionStyleDefault) {
                if (continuation.isActive) continuation.resume(true)
            },
        )
        continuation.invokeOnCancellation { alert.dismissViewControllerAnimated(true, completion = null) }
        presenter.presentViewController(alert, animated = true, completion = null)
    }
}

private fun UIViewController.topPresentedController(): UIViewController {
    var current = this
    while (current.presentedViewController != null) current = requireNotNull(current.presentedViewController)
    return current
}
