package ai.meteor.kcode.plugin.api

import android.app.Activity
import android.content.Context
import java.util.concurrent.atomic.AtomicReference

/** The active host Activity is supplied by deployment and released on retirement. */
class AndroidPluginHostInputs private constructor(
    activityProvider: () -> Activity,
    foregroundProvider: () -> AndroidForegroundExecutionHost,
    permissionProvider: () -> AndroidPermissionHost?,
    dialogProvider: () -> ConfirmationDialogHost,
    windowProvider: () -> AndroidPluginWindowHost = { NativeAndroidPluginWindowHost(activityProvider().applicationContext) },
) : PluginHostInputs() {
    constructor(activity: Activity) : this({ activity }, { NativeAndroidForegroundExecutionHost(activity.applicationContext) }, { null }, { AndroidConfirmationDialogHost { activity } })
    constructor(activity: Activity, foregroundExecution: AndroidForegroundExecutionHost) : this({ activity }, { foregroundExecution }, { null }, { AndroidConfirmationDialogHost { activity } })

    constructor(activity: Activity, permissions: AndroidPermissionHost) : this(
        { activity }, { NativeAndroidForegroundExecutionHost(activity.applicationContext) }, { permissions }, { AndroidConfirmationDialogHost { activity } },
    )

    constructor(activity: Activity, permissions: AndroidPermissionHost?, dialogs: ConfirmationDialogHost) : this(
        { activity }, { NativeAndroidForegroundExecutionHost(activity.applicationContext) }, { permissions }, { dialogs },
    )

    private val windowSource = AtomicReference<(() -> AndroidPluginWindowHost)?>(windowProvider)
    fun windows(): AndroidPluginWindowHost = AndroidPluginWindowHost { factory ->
        checkNotNull(windowSource.get()) { "Android host inputs are closed" }.invoke().open(factory)
    }

    private val dialogSource = AtomicReference<(() -> ConfirmationDialogHost)?>(dialogProvider)
    override fun confirmationDialogs(): ConfirmationDialogHost = ConfirmationDialogHost { request ->
        checkNotNull(dialogSource.get()) { "Android host inputs are closed" }.invoke().confirm(request)
    }

    private val permissionSource = AtomicReference<(() -> AndroidPermissionHost?)?>(permissionProvider)
    private val source = AtomicReference<(() -> Activity)?>(activityProvider)
    private val foregroundSource = AtomicReference<(() -> AndroidForegroundExecutionHost)?>(foregroundProvider)

    fun activity(): Activity = checkNotNull(source.get()) { "Android host inputs are closed" }.invoke()

    fun applicationContext(): Context = activity().applicationContext

    fun foregroundExecution(): AndroidForegroundExecutionHost =
        AndroidForegroundExecutionHost { notification, serviceType ->
            checkNotNull(foregroundSource.get()) { "Android host inputs are closed" }.invoke().acquire(notification, serviceType)
        }

    private fun permissionHost(): AndroidPermissionHost? =
        checkNotNull(permissionSource.get()) { "Android host inputs are closed" }.invoke()

    fun permissions(): AndroidPermissionHost? {
        if (permissionHost() == null) return null
        return object : AndroidPermissionHost {
            override fun isGranted(permission: String): Boolean = requireNotNull(permissionHost()).isGranted(permission)
            override suspend fun request(permission: String): Boolean = requireNotNull(permissionHost()).request(permission)
        }
    }

    override fun createLease(): AndroidPluginHostInputs {
        checkNotNull(source.get()) { "Android host inputs are closed" }
        return AndroidPluginHostInputs({ activity() }, { foregroundExecution() }, { permissions() }, { confirmationDialogs() }, { windows() })
    }

    override suspend fun close() {
        source.set(null)
        foregroundSource.set(null)
        permissionSource.set(null)
        dialogSource.set(null)
        windowSource.set(null)
    }
}
