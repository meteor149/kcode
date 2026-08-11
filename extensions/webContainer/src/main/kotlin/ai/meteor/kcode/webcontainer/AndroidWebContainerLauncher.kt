package ai.meteor.kcode.webcontainer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AndroidWebContainerLauncher(context: Context) : WebContainerController {
    private val appContext = context.applicationContext

    override suspend fun launch(request: WebPreviewRequest): WebPreviewResult {
        val entrySize = when (request.source) {
            is WebPreviewSource.WorkspaceFile -> WebWorkspace.resolveEntry(appContext, request.entryPath).length()
            is WebPreviewSource.RemoteWebsite -> 0L
        }
        val containerId = UUID.randomUUID().toString()
        withContext(Dispatchers.Main.immediate) {
            AndroidWebContainerSessions.closeAll()
            AndroidWebContainerSessions.open(containerId, request)
            appContext.startActivity(previewIntent(containerId, request, reorderToFront = false))
        }
        return WebPreviewResult(containerId, request.entryPath, entrySize, PRESENTATION)
    }

    override suspend fun list(): List<WebContainerInfo> = AndroidWebContainerSessions.list()

    override suspend fun screenshot(containerId: String): WebContainerScreenshot {
        val activity = AndroidWebContainerSessions.awaitActivity(containerId)
        return withContext(Dispatchers.Main.immediate) {
            while (activity.webView.width <= 0 || activity.webView.height <= 0) delay(16)
            AndroidWebContainerSessions.screenshot(containerId, activity)
        }
    }

    override suspend fun inspect(containerId: String): WebPageInspection {
        val activity = AndroidWebContainerSessions.awaitActivity(containerId)
        return withContext(Dispatchers.Main.immediate) {
            decodeWebInspection(containerId, activity.evaluateDebugScript(WebDebugScript.inspect))
        }
    }

    override suspend fun interact(request: WebInteractionRequest): WebInteractionResult {
        val activity = AndroidWebContainerSessions.awaitActivity(request.containerId)
        val target = withContext(Dispatchers.Main.immediate) {
            when (request.action) {
                WebInteractionAction.Reload -> activity.webView.reload().let { "page" }
                WebInteractionAction.Back -> {
                    require(activity.webView.canGoBack()) { "Web container has no page to go back to" }
                    activity.webView.goBack()
                    "history"
                }
                else -> decodeWebInteractionTarget(activity.evaluateDebugScript(WebDebugScript.interact(request)))
            }
        }
        return WebInteractionResult(request.containerId, request.action, target)
    }

    override suspend fun console(containerId: String, cursor: Long, limit: Int): WebConsoleSnapshot =
        AndroidWebContainerSessions.console(containerId, cursor, limit)

    override suspend fun setState(containerId: String, state: WebContainerState): WebContainerInfo =
        withContext(Dispatchers.Main.immediate) {
            val info = AndroidWebContainerSessions.requireInfo(containerId)
            when (state) {
                WebContainerState.Foreground -> appContext.startActivity(
                    previewIntent(containerId, WebPreviewRequest(info.entryPath, info.title), reorderToFront = true),
                )
                WebContainerState.Background -> {
                    val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                        ?: error("Could not return to kcode while keeping Web container $containerId running")
                    appContext.startActivity(
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                }
            }
            AndroidWebContainerSessions.setState(containerId, state)
        }

    override suspend fun close(containerId: String) = withContext(Dispatchers.Main.immediate) {
        AndroidWebContainerSessions.close(containerId)
    }

    companion object {
        const val PRESENTATION = "android-webview"

        /** Returns the visible preview host so app-level tool confirmation can stay above it. */
        fun activeContainerActivity(): android.app.Activity? = AndroidWebContainerSessions.activeActivity()
    }

    private fun previewIntent(
        containerId: String,
        request: WebPreviewRequest,
        reorderToFront: Boolean,
    ): Intent =
        Intent(appContext, WebContainerActivity::class.java)
            .putExtra(WebContainerActivity.EXTRA_ENTRY_PATH, request.entryPath)
            .putExtra(WebContainerActivity.EXTRA_TITLE, request.title)
            .putExtra(WebContainerActivity.EXTRA_CONTAINER_ID, containerId)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or (if (reorderToFront) {
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                } else {
                    0
                }),
            )
}

internal object AndroidWebContainerSessions {
    private data class Session(
        var info: WebContainerInfo,
        var activity: WeakReference<WebContainerActivity>? = null,
        val ready: CompletableDeferred<WebContainerActivity> = CompletableDeferred(),
        val console: ArrayDeque<WebConsoleEntry> = ArrayDeque(),
        var nextConsoleSequence: Long = 1,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun open(id: String, request: WebPreviewRequest) {
        sessions[id] = Session(
            WebContainerInfo(id, request.entryPath, request.title, "android-webview", WebContainerState.Foreground),
        )
    }

    fun attach(id: String, activity: WebContainerActivity) {
        sessions[id]?.let { session ->
            session.activity = WeakReference(activity)
            session.ready.complete(activity)
        }
    }

    fun detach(id: String, activity: WebContainerActivity) {
        sessions[id]?.takeIf { it.activity?.get() === activity }?.let { sessions.remove(id) }
    }

    fun list(): List<WebContainerInfo> = sessions.values.map { it.info }.sortedBy { it.id }

    fun requireInfo(id: String): WebContainerInfo = sessions[id]?.info
        ?: error("Web container is not running: $id")

    fun setState(id: String, state: WebContainerState): WebContainerInfo {
        val session = sessions[id] ?: error("Web container is not running: $id")
        session.info = session.info.copy(state = state)
        return session.info
    }

    fun activeActivity(): WebContainerActivity? = sessions.values
        .mapNotNull { it.activity?.get() }
        .firstOrNull { !it.isFinishing && !it.isDestroyed }

    suspend fun awaitActivity(id: String): WebContainerActivity {
        val session = sessions[id] ?: error("Web container is not running: $id")
        return session.activity?.get() ?: session.ready.await()
    }

    fun screenshot(id: String, activity: WebContainerActivity): WebContainerScreenshot {
        require(sessions[id]?.activity?.get() === activity) { "Web container is not running: $id" }
        val view = activity.webView
        require(view.width > 0 && view.height > 0) { "Web container is not ready for a screenshot: $id" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode Web screenshot" }
            output.toByteArray()
        }
        bitmap.recycle()
        return WebContainerScreenshot(id, bytes, view.width, view.height)
    }

    fun recordConsole(id: String, level: String, message: String, source: String?, line: Int?) {
        val session = sessions[id] ?: return
        synchronized(session) {
            session.console.addLast(
                WebConsoleEntry(session.nextConsoleSequence++, level, message.take(16_384), source, line),
            )
            while (session.console.size > MAX_CONSOLE_ENTRIES) session.console.removeFirst()
        }
    }

    fun console(id: String, cursor: Long, limit: Int): WebConsoleSnapshot {
        val session = sessions[id] ?: error("Web container is not running: $id")
        return synchronized(session) {
            val entries = session.console.filter { it.sequence > cursor }.take(limit)
            WebConsoleSnapshot(id, entries, entries.lastOrNull()?.sequence ?: cursor)
        }
    }

    fun close(id: String) {
        val session = sessions.remove(id) ?: error("Web container is not running: $id")
        session.activity?.get()?.finish()
    }

    fun closeAll() {
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { it.activity?.get()?.finish() }
    }

    private const val MAX_CONSOLE_ENTRIES = 500
}
