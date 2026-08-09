package ai.meteor.kcode.h5

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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

class AndroidH5ContainerLauncher(context: Context) : H5ContainerController {
    private val appContext = context.applicationContext

    override suspend fun launch(request: H5PreviewRequest): H5PreviewResult {
        val entry = H5Workspace.resolveEntry(appContext, request.entryPath)
        val containerId = UUID.randomUUID().toString()
        withContext(Dispatchers.Main.immediate) {
            AndroidH5Sessions.closeAll()
            AndroidH5Sessions.open(containerId, request)
            appContext.startActivity(previewIntent(containerId, request, reorderToFront = false))
        }
        return H5PreviewResult(containerId, request.entryPath, entry.length(), PRESENTATION)
    }

    override suspend fun list(): List<H5ContainerInfo> = AndroidH5Sessions.list()

    override suspend fun screenshot(containerId: String): H5ContainerScreenshot {
        val activity = AndroidH5Sessions.awaitActivity(containerId)
        return withContext(Dispatchers.Main.immediate) {
            withTimeout(10_000) {
                while (activity.webView.width <= 0 || activity.webView.height <= 0) delay(16)
            }
            AndroidH5Sessions.screenshot(containerId, activity)
        }
    }

    override suspend fun inspect(containerId: String): H5PageInspection {
        val activity = AndroidH5Sessions.awaitActivity(containerId)
        return withContext(Dispatchers.Main.immediate) {
            decodeH5Inspection(containerId, activity.evaluateDebugScript(H5DebugScript.inspect))
        }
    }

    override suspend fun interact(request: H5InteractionRequest): H5InteractionResult {
        val activity = AndroidH5Sessions.awaitActivity(request.containerId)
        val target = withContext(Dispatchers.Main.immediate) {
            when (request.action) {
                H5InteractionAction.Reload -> activity.webView.reload().let { "page" }
                H5InteractionAction.Back -> {
                    require(activity.webView.canGoBack()) { "H5 container has no page to go back to" }
                    activity.webView.goBack()
                    "history"
                }
                else -> decodeH5InteractionTarget(activity.evaluateDebugScript(H5DebugScript.interact(request)))
            }
        }
        return H5InteractionResult(request.containerId, request.action, target)
    }

    override suspend fun console(containerId: String, cursor: Long, limit: Int): H5ConsoleSnapshot =
        AndroidH5Sessions.console(containerId, cursor, limit)

    override suspend fun setState(containerId: String, state: H5ContainerState): H5ContainerInfo =
        withContext(Dispatchers.Main.immediate) {
            val info = AndroidH5Sessions.requireInfo(containerId)
            when (state) {
                H5ContainerState.Foreground -> appContext.startActivity(
                    previewIntent(containerId, H5PreviewRequest(info.entryPath, info.title), reorderToFront = true),
                )
                H5ContainerState.Background -> {
                    val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                        ?: error("Could not return to kcode while keeping H5 container $containerId running")
                    appContext.startActivity(
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                }
            }
            AndroidH5Sessions.setState(containerId, state)
        }

    override suspend fun close(containerId: String) = withContext(Dispatchers.Main.immediate) {
        AndroidH5Sessions.close(containerId)
    }

    companion object {
        const val PRESENTATION = "android-webview"

        /** Returns the visible preview host so app-level tool confirmation can stay above it. */
        fun activeContainerActivity(): android.app.Activity? = AndroidH5Sessions.activeActivity()
    }

    private fun previewIntent(
        containerId: String,
        request: H5PreviewRequest,
        reorderToFront: Boolean,
    ): Intent =
        Intent(appContext, H5ContainerActivity::class.java)
            .putExtra(H5ContainerActivity.EXTRA_ENTRY_PATH, request.entryPath)
            .putExtra(H5ContainerActivity.EXTRA_TITLE, request.title)
            .putExtra(H5ContainerActivity.EXTRA_CONTAINER_ID, containerId)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or (if (reorderToFront) {
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                } else {
                    0
                }),
            )
}

internal object AndroidH5Sessions {
    private data class Session(
        var info: H5ContainerInfo,
        var activity: WeakReference<H5ContainerActivity>? = null,
        val ready: CompletableDeferred<H5ContainerActivity> = CompletableDeferred(),
        val console: ArrayDeque<H5ConsoleEntry> = ArrayDeque(),
        var nextConsoleSequence: Long = 1,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun open(id: String, request: H5PreviewRequest) {
        sessions[id] = Session(
            H5ContainerInfo(id, request.entryPath, request.title, "android-webview", H5ContainerState.Foreground),
        )
    }

    fun attach(id: String, activity: H5ContainerActivity) {
        sessions[id]?.let { session ->
            session.activity = WeakReference(activity)
            session.ready.complete(activity)
        }
    }

    fun detach(id: String, activity: H5ContainerActivity) {
        sessions[id]?.takeIf { it.activity?.get() === activity }?.let { sessions.remove(id) }
    }

    fun list(): List<H5ContainerInfo> = sessions.values.map { it.info }.sortedBy { it.id }

    fun requireInfo(id: String): H5ContainerInfo = sessions[id]?.info
        ?: error("H5 container is not running: $id")

    fun setState(id: String, state: H5ContainerState): H5ContainerInfo {
        val session = sessions[id] ?: error("H5 container is not running: $id")
        session.info = session.info.copy(state = state)
        return session.info
    }

    fun activeActivity(): H5ContainerActivity? = sessions.values
        .mapNotNull { it.activity?.get() }
        .firstOrNull { !it.isFinishing && !it.isDestroyed }

    suspend fun awaitActivity(id: String): H5ContainerActivity {
        val session = sessions[id] ?: error("H5 container is not running: $id")
        return withTimeout(10_000) { session.activity?.get() ?: session.ready.await() }
    }

    fun screenshot(id: String, activity: H5ContainerActivity): H5ContainerScreenshot {
        require(sessions[id]?.activity?.get() === activity) { "H5 container is not running: $id" }
        val view = activity.webView
        require(view.width > 0 && view.height > 0) { "H5 container is not ready for a screenshot: $id" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode H5 screenshot" }
            output.toByteArray()
        }
        bitmap.recycle()
        return H5ContainerScreenshot(id, bytes, view.width, view.height)
    }

    fun recordConsole(id: String, level: String, message: String, source: String?, line: Int?) {
        val session = sessions[id] ?: return
        synchronized(session) {
            session.console.addLast(
                H5ConsoleEntry(session.nextConsoleSequence++, level, message.take(16_384), source, line),
            )
            while (session.console.size > MAX_CONSOLE_ENTRIES) session.console.removeFirst()
        }
    }

    fun console(id: String, cursor: Long, limit: Int): H5ConsoleSnapshot {
        val session = sessions[id] ?: error("H5 container is not running: $id")
        return synchronized(session) {
            val entries = session.console.filter { it.sequence > cursor }.take(limit)
            H5ConsoleSnapshot(id, entries, entries.lastOrNull()?.sequence ?: cursor)
        }
    }

    fun close(id: String) {
        val session = sessions.remove(id) ?: error("H5 container is not running: $id")
        session.activity?.get()?.finish()
    }

    fun closeAll() {
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { it.activity?.get()?.finish() }
    }

    private const val MAX_CONSOLE_ENTRIES = 500
}
