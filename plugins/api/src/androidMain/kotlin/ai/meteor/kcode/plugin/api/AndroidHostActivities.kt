package ai.meteor.kcode.plugin.api

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Neutral foreground window selection for native hosts, including auxiliary Activities. */
class AndroidHostActivities(private val application: Application) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private var resumed = WeakReference<Activity>(null)
    init { application.registerActivityLifecycleCallbacks(this) }
    fun current(): Activity? = resumed.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) { if (resumed.get() === activity) resumed.clear() }
    override fun onActivityDestroyed(activity: Activity) { if (resumed.get() === activity) resumed.clear() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun close() { resumed.clear(); application.unregisterActivityLifecycleCallbacks(this) }
}
