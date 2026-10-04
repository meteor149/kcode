package ai.meteor.kcode.plugin.api

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Generic runtime-permission primitive; callers decide which permission and when. */
interface AndroidPermissionHost {
    fun isGranted(permission: String): Boolean
    suspend fun request(permission: String): Boolean
}

/** ActivityResult adapter: callback names correlate results; canceled callers do not consume new results. */
class AndroidPermissionRequestBroker(
    context: () -> Context,
    launch: (String) -> Unit,
) : AndroidPermissionHost, AutoCloseable {
    private val contextSource = AtomicReference<(() -> Context)?>(context)
    private val launchSource = AtomicReference<((String) -> Unit)?>(launch)
    private val mutex = Mutex()
    private class Pending(val permission: String, val result: CompletableDeferred<Boolean> = CompletableDeferred())
    private var pending: Pending? = null

    private fun validate(permission: String) {
        require(permission.isNotBlank() && '\u0000' !in permission) { "Invalid Android permission name" }
    }

    override fun isGranted(permission: String): Boolean {
        validate(permission)
        return checkNotNull(contextSource.get()) { "Android permission host is closed" }.invoke()
            .checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun request(permission: String): Boolean = withContext(Dispatchers.Main.immediate) {
        validate(permission)
        mutex.withLock {
            checkNotNull(launchSource.get()) { "Android permission host is closed" }
            // Cancellation cannot dismiss an OS dialog: the next caller waits for its actual completion.
            pending?.result?.await()
            if (isGranted(permission)) return@withLock true
            val request = Pending(permission)
            pending = request
            try {
                checkNotNull(launchSource.get()) { "Android permission host is closed" }.invoke(permission)
            } catch (error: Throwable) {
                if (pending === request) pending = null
                request.result.complete(false)
                throw error
            }
            request.result.await()
        }
    }

    fun onResult(results: Map<String, Boolean>) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Permission results must arrive on Main" }
        val request = pending ?: return
        if (results.isEmpty()) onResult(request.permission, false)
        else results[request.permission]?.let { onResult(request.permission, it) }
    }

    fun onResult(permission: String, granted: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Permission results must arrive on Main" }
        val request = pending?.takeIf { it.permission == permission } ?: return
        pending = null
        request.result.complete(granted)
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Permission host must close on Main" }
        launchSource.set(null)
        contextSource.set(null)
        pending?.result?.complete(false)
        pending = null
    }
}
