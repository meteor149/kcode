package ai.meteor.kcode.plugin.api

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native content is provided by a plugin; the manifest component contains no product UI. */
interface AndroidPluginWindowContent {
    fun onCreate(savedInstanceState: Bundle?)
    fun onResume() = Unit
    fun onPause() = Unit
    fun onNewIntent(intent: Intent) = Unit
    fun onDestroy() = Unit
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray): Boolean = false
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean = false
    suspend fun close()
}

fun interface AndroidPluginWindowFactory {
    fun create(activity: Activity): AndroidPluginWindowContent
}

interface AndroidPluginWindow {
    suspend fun awaitReady()
    suspend fun show()
    /** Idempotent; returns only after plugin content and its async cleanup have stopped. */
    suspend fun close()
}

fun interface AndroidPluginWindowHost {
    suspend fun open(factory: AndroidPluginWindowFactory): AndroidPluginWindow
}

class NativeAndroidPluginWindowHost(context: Context) : AndroidPluginWindowHost {
    private val context = context.applicationContext
    override suspend fun open(factory: AndroidPluginWindowFactory): AndroidPluginWindow {
        var registration: AndroidWindowRegistration? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                val created = AndroidPluginWindows.register(context, factory)
                registration = created
                // Each lease has its own task; foregrounding must select this lease's Activity.
                context.startActivity(Intent(context, PluginWindowActivity::class.java)
                    .putExtra(AndroidPluginWindows.TokenExtra, created.token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                currentCoroutineContext().ensureActive()
                created
            }
        } catch (error: Throwable) {
            try { registration?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

}

internal object AndroidPluginWindows {
    const val TokenExtra = "ai.meteor.kcode.plugin.api.window-token"
    private val records = mutableMapOf<String, AndroidWindowRegistration>()
    fun register(context: Context, factory: AndroidPluginWindowFactory): AndroidWindowRegistration = AndroidWindowRegistration(
        UUID.randomUUID().toString(), context, factory,
    ).also { records[it.token] = it }
    fun find(token: String?): AndroidWindowRegistration? = records[token]
    fun remove(record: AndroidWindowRegistration) { if (records[record.token] === record) records.remove(record.token) }
}

internal class AndroidWindowRegistration(
    val token: String,
    private var context: Context?,
    private var factory: AndroidPluginWindowFactory?,
) : AndroidPluginWindow {
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupOwner = PluginOperationOwner("Native window cleanup")
    private var activity = WeakReference<PluginWindowActivity>(null)
    private var content: AndroidPluginWindowContent? = null
    private var closing: Job? = null
    private var closed = false
    private val failures = mutableListOf<Throwable>()

    fun attach(host: PluginWindowActivity, savedState: Bundle?): Boolean {
        if (closed || activity.get() != null) return false
        activity = WeakReference(host)
        try {
            val created = requireNotNull(factory).create(host)
            content = created
            created.onCreate(savedState)
            ready.complete(Unit)
            return true
        } catch (error: Throwable) {
            failures += error
            ready.completeExceptionally(error)
            retire()
            return false
        }
    }

    fun callback(block: (AndroidPluginWindowContent) -> Unit) {
        if (!closed) content?.let { value ->
            try { block(value) } catch (error: Throwable) { failures += error; retire() }
        }
    }

    fun permissions(code: Int, names: Array<out String>, results: IntArray): Boolean {
        var handled = false
        callback { handled = it.onRequestPermissionsResult(code, names, results) }
        return handled
    }

    fun result(code: Int, result: Int, data: Intent?): Boolean {
        var handled = false
        callback { handled = it.onActivityResult(code, result, data) }
        return handled
    }

    fun detached(host: PluginWindowActivity) {
        if (activity.get() === host) retire()
    }

    private fun retire() {
        if (closed) return
        closed = true
        AndroidPluginWindows.remove(this)
        factory = null
        context = null
        if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("Plugin window retired before creation"))
        val owned = content
        content = null
        val host = activity.get()
        activity.clear()
        host?.releaseRegistration(this)
        val cleanup = scope.launch(start = CoroutineStart.LAZY) {
            withContext(NonCancellable) {
                if (owned != null) {
                    runCatching {
                        cleanupOwner.run {
                            runCatching { owned.onDestroy() }.exceptionOrNull()?.let(failures::add)
                            owned.close()
                        }
                    }.exceptionOrNull()?.let(failures::add)
                }
                runCatching { cleanupOwner.close() }.exceptionOrNull()?.let(failures::add)
                runCatching { host?.takeUnless { it.isDestroyed }?.let { it.setContentView(FrameLayout(it)) } }.exceptionOrNull()?.let(failures::add)
                runCatching { host?.finish() }.exceptionOrNull()?.let(failures::add)
            }
        }
        closing = cleanup
        cleanup.invokeOnCompletion { scope.cancel() }
        cleanup.start()
    }

    override suspend fun awaitReady() = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "Plugin window is closed" }
        ready.await()
        check(!closed) { "Plugin window is closed" }
    }

    override suspend fun show() = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "Plugin window is closed" }
        val manager = requireNotNull(context).getSystemService(ActivityManager::class.java)
        var task: ActivityManager.AppTask? = null
        // Android may defer creating an occluded Activity. Identify its task by lease before awaiting content.
        for (attempt in 0 until 100) {
            check(!closed) { "Plugin window is closed" }
            task = manager.appTasks.firstOrNull {
                it.taskInfo.baseIntent.getStringExtra(AndroidPluginWindows.TokenExtra) == token
            }
            if (task != null) break
            delay(50)
        }
        checkNotNull(task) { "Plugin window task is not available" }.moveToFront()
        ready.await()
        check(!closed) { "Plugin window is closed" }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        cleanupOwner.requireCanClose()
        retire()
        closing?.join()
        if (failures.isNotEmpty()) throw PluginCleanupException("Native plugin window", failures.toList())
    }
}

/** Stable OS entry. Product views, permissions policy and capability implementations live in plugins. */
class PluginWindowActivity : ComponentActivity() {
    private var registration: AndroidWindowRegistration? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val record = AndroidPluginWindows.find(intent.getStringExtra(AndroidPluginWindows.TokenExtra))
        registration = record
        if (record?.attach(this, savedInstanceState) != true) finish()
    }
    override fun onResume() { super.onResume(); registration?.callback { it.onResume() } }
    override fun onPause() { registration?.callback { it.onPause() }; super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); registration?.callback { it.onNewIntent(intent) } }
    override fun onDestroy() { registration?.detached(this); registration = null; super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (registration?.permissions(requestCode, permissions, grantResults) != true) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
    @Deprecated("Generic compatibility callback for native plugin content")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (registration?.result(requestCode, resultCode, data) != true) super.onActivityResult(requestCode, resultCode, data)
    }
    internal fun releaseRegistration(value: AndroidWindowRegistration) { if (registration === value) registration = null }
}
