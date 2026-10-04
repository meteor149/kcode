package ai.meteor.kcode.plugin.api

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** UI mechanics only; callers supply all product strings and own the suspended operation. */
class AndroidConfirmationDialogHost(activity: () -> Activity?) : ConfirmationDialogHost, AutoCloseable {
    private val source = AtomicReference<(() -> Activity?)?>(activity)
    private val dialogs = mutableSetOf<AlertDialog>()

    override suspend fun confirm(request: ConfirmationDialogRequest): Boolean = withContext(Dispatchers.Main.immediate) {
        val activity = source.get()?.invoke() ?: return@withContext false
        if (activity.isFinishing || activity.isDestroyed) return@withContext false
        var dialog: AlertDialog? = null
        try {
            suspendCancellableCoroutine { continuation ->
                dialog = AlertDialog.Builder(activity)
                    .setTitle(request.title)
                    .setMessage(request.message)
                    .setPositiveButton(request.confirmLabel) { _, _ ->
                        if (continuation.isActive) continuation.resume(true)
                    }
                    .setNegativeButton(request.cancelLabel) { _, _ ->
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
                    .setOnDismissListener { if (continuation.isActive) continuation.resume(false) }
                    .create()
                dialog?.let { dialogs += it; if (continuation.isActive) it.show() }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                dialog?.let { dialogs.remove(it); it.dismiss() }
            }
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Dialog host must close on Main" }
        source.set(null)
        dialogs.toList().forEach { it.dismiss() }
    }
}
