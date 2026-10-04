package ai.meteor.kcode.plugin.webcontainer.native

import ai.meteor.kcode.plugin.api.AndroidPluginWindow
import ai.meteor.kcode.plugin.api.AndroidPluginWindowFactory
import ai.meteor.kcode.plugin.api.AndroidPluginWindowHost
import ai.meteor.kcode.webcontainer.WebConsoleEntry
import ai.meteor.kcode.webcontainer.WebConsoleSnapshot
import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerScreenshot
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.webcontainer.WebInteractionAction
import ai.meteor.kcode.webcontainer.WebInteractionRequest
import ai.meteor.kcode.webcontainer.WebInteractionResult
import ai.meteor.kcode.webcontainer.WebPageInspection
import ai.meteor.kcode.webcontainer.WebPreviewRequest
import ai.meteor.kcode.webcontainer.WebPreviewResult
import ai.meteor.kcode.webcontainer.WebPreviewSource
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidWebContainerLauncher(
    private val appContext: Context,
    private val windows: AndroidPluginWindowHost,
    private val resources: AndroidWebPluginResources,
) : WebContainerController {
    private val sessions = AndroidWebContainerSessions()
    private val launchLock = Mutex()

    override suspend fun launch(request: WebPreviewRequest): WebPreviewResult = launchLock.withLock {
        val size = when (request.source) {
            is WebPreviewSource.WorkspaceFile -> WebWorkspace.resolveEntry(appContext, request.entryPath).length()
            is WebPreviewSource.RemoteWebsite -> 0L
        }
        withContext(Dispatchers.Main.immediate) {
            // Preserve the product's single-preview policy within this mount, never across mounts.
            sessions.closeAll()
            val session = sessions.open(request)
            try {
                val window = windows.open(AndroidPluginWindowFactory { activity ->
                    AndroidWebContainerContent(activity, resources, sessions, request, session.info.id)
                        .also { session.content = it }
                })
                session.window = window
                session.windowReady.complete(window)
                window.show()
                WebPreviewResult(session.info.id, request.entryPath, size, "android-webview")
            } catch (error: Throwable) {
                session.windowReady.complete(null)
                withContext(NonCancellable) {
                    try { sessions.discard(session) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                }
                throw error
            }
        }
    }

    override suspend fun list(): List<WebContainerInfo> = withContext(Dispatchers.Main.immediate) { sessions.list() }

    override suspend fun screenshot(containerId: String): WebContainerScreenshot = withContext(Dispatchers.Main.immediate) {
        val content = sessions.requireContent(containerId)
        while (content.webView.width <= 0 || content.webView.height <= 0) {
            sessions.requireSession(containerId)
            delay(16)
        }
        sessions.screenshot(containerId, content)
    }

    override suspend fun inspect(containerId: String): WebPageInspection = withContext(Dispatchers.Main.immediate) {
        decodeWebInspection(containerId, sessions.requireContent(containerId).evaluateDebugScript(WebDebugScript.inspect))
    }

    override suspend fun interact(request: WebInteractionRequest): WebInteractionResult = withContext(Dispatchers.Main.immediate) {
        val content = sessions.requireContent(request.containerId)
        val target = when (request.action) {
            WebInteractionAction.Reload -> content.webView.reload().let { "page" }
            WebInteractionAction.Back -> {
                require(content.webView.canGoBack()) { "Web container has no page to go back to" }
                content.webView.goBack()
                "history"
            }
            else -> decodeWebInteractionTarget(content.evaluateDebugScript(WebDebugScript.interact(request)))
        }
        WebInteractionResult(request.containerId, request.action, target)
    }

    override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot =
        withContext(Dispatchers.Main.immediate) { sessions.console(containerId, cursor, limit) }

    override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo =
        withContext(Dispatchers.Main.immediate) {
            val session = sessions.requireSession(containerId)
            when (state) {
                WebContainerState.Foreground -> requireNotNull(session.window).show()
                WebContainerState.Background -> {
                    val intent = requireNotNull(appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)) {
                        "Could not return to kcode while keeping Web container $containerId running"
                    }
                    appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
            sessions.setState(containerId, state)
        }

    override suspend fun close(containerId: String) = launchLock.withLock {
        withContext(Dispatchers.Main.immediate) { sessions.close(containerId) }
    }
    override suspend fun closeAll() = launchLock.withLock {
        withContext(Dispatchers.Main.immediate) { sessions.closeAll() }
    }
    suspend fun dispose() = withContext(NonCancellable + Dispatchers.Main.immediate) { sessions.dispose() }
}

/** Main-thread state is private to one plugin mount. Routing through the SDK holds no product state. */
internal class AndroidWebContainerSessions {
    class Session(var info: WebContainerInfo) {
        var window: AndroidPluginWindow? = null
        val windowReady = CompletableDeferred<AndroidPluginWindow?>()
        var content: AndroidWebContainerContent? = null
        val console = ArrayDeque<WebConsoleEntry>()
        var nextConsoleSequence = 1L
    }

    private val sessions = mutableMapOf<String, Session>()
    private val owned = mutableSetOf<Session>()
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(cleanupJob + Dispatchers.Main.immediate)
    private val failures = mutableListOf<Throwable>()

    fun open(request: WebPreviewRequest): Session = Session(WebContainerInfo(
        UUID.randomUUID().toString(), request.entryPath, request.title, "android-webview", WebContainerState.Foreground,
    )).also { sessions[it.info.id] = it; owned += it }

    fun detached(id: String) {
        val session = sessions.remove(id) ?: return
        // Independent context: closing the same SDK lease from its own cleanup callback is forbidden.
        cleanupScope.launch {
            runCatching { session.windowReady.await()?.close() }.exceptionOrNull()?.let(failures::add)
            session.content = null
            owned.remove(session)
        }
    }

    fun list(): List<WebContainerInfo> = sessions.values.map { it.info }.sortedBy { it.id }
    fun requireSession(id: String): Session = requireNotNull(sessions[id]) { "Web container is not running: $id" }
    suspend fun requireContent(id: String): AndroidWebContainerContent {
        val session = requireSession(id)
        requireNotNull(session.window).awaitReady()
        return requireNotNull(session.content).also { check(!it.resourcesClosed) { "Web container is closed: $id" } }
    }

    fun setState(id: String, state: WebContainerState): WebContainerInfo {
        val session = requireSession(id)
        session.info = session.info.copy(state = state)
        return session.info
    }

    fun screenshot(id: String, content: AndroidWebContainerContent): WebContainerScreenshot {
        check(requireSession(id).content === content)
        val view = content.webView
        require(view.width > 0 && view.height > 0) { "Web container is not ready for a screenshot: $id" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode Web screenshot" }
                output.toByteArray()
            }
            return WebContainerScreenshot(id, bytes, view.width, view.height)
        } finally { bitmap.recycle() }
    }

    fun recordConsole(id: String, level: String, message: String, source: String?, line: Int?) {
        val session = sessions[id] ?: return
        session.console.addLast(WebConsoleEntry(session.nextConsoleSequence++, level, message.take(16_384), source, line))
        while (session.console.size > 500) session.console.removeFirst()
    }

    fun console(id: String, cursor: Long, limit: Int): WebConsoleSnapshot {
        require(limit > 0) { "Console limit must be positive" }
        val entries = requireSession(id).console.filter { it.sequence > cursor }.take(limit)
        return WebConsoleSnapshot(id, entries, entries.lastOrNull()?.sequence ?: cursor)
    }

    suspend fun close(id: String) {
        val session = requireSession(id)
        discard(session)
    }

    suspend fun discard(session: Session) = withContext(NonCancellable) {
        sessions.remove(session.info.id)
        try { session.windowReady.await()?.close() } finally { session.content = null; owned.remove(session) }
    }

    suspend fun closeAll() = withContext(NonCancellable) {
        sessions.clear()
        for (session in owned.toList()) {
            runCatching { session.windowReady.await()?.close() }.exceptionOrNull()?.let(failures::add)
            session.content = null
            owned.remove(session)
        }
        // OS destruction observers may still be unwinding after the window cleanup job completes.
        cleanupJob.children.toList().forEach { it.join() }
        if (failures.isNotEmpty()) {
            val errors = failures.toList()
            failures.clear()
            throw ai.meteor.kcode.plugin.api.PluginCleanupException("Android Web sessions", errors)
        }
    }

    suspend fun dispose() {
        try { closeAll() } finally { cleanupJob.cancelAndJoin() }
    }
}
