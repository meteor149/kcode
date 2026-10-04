package ai.meteor.kcode.plugin.api

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** An OS lease: notification content and acquisition policy belong to the calling plugin. */
fun interface AndroidForegroundExecutionHost {
    fun acquire(notification: Notification, serviceType: Int): AutoCloseable
}

internal class NativeAndroidForegroundExecutionHost(context: Context) : AndroidForegroundExecutionHost {
    private val context = context.applicationContext
    override fun acquire(notification: Notification, serviceType: Int): AutoCloseable =
        ForegroundExecutionLeases.acquire(context, notification, serviceType)
}

/** Process-local coordination is required because Android exposes one foreground Service instance. */
internal object ForegroundExecutionLeases {
    private data class Request(val notification: Notification, val serviceType: Int)
    private val requests = linkedMapOf<String, Request>()

    @Synchronized
    fun acquire(context: Context, notification: Notification, serviceType: Int): AutoCloseable {
        require(serviceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) { "Undeclared foreground service type" }
        val token = UUID.randomUUID().toString()
        requests[token] = Request(notification, serviceType)
        try {
            context.startForegroundService(Intent(context, PluginForegroundExecutionService::class.java))
        } catch (error: Throwable) {
            requests.remove(token)
            throw error
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) release(context, token)
        }
    }

    @Synchronized
    private fun release(context: Context, token: String) {
        if (requests.remove(token) == null) return
        val intent = Intent(context, PluginForegroundExecutionService::class.java)
        if (requests.isEmpty()) context.stopService(intent) else context.startService(intent)
    }

    @Synchronized
    fun current(): Pair<Notification, Int>? = requests.values.lastOrNull()?.let { it.notification to it.serviceType }
}

/** Stable manifest entry; it contains no generation, channel, or UI policy. */
class PluginForegroundExecutionService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = ForegroundExecutionLeases.current()
        if (request == null) stopSelf(startId)
        else startForeground(1001, request.first, request.second)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf(startId) }
}
