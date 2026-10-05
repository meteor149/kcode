package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.StoredAppSettings
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

/** Execution identity policy; implementations must reject retained readers after withdrawal. */
interface ShellModeSettingsPolicy {
    fun resolve(settings: StoredAppSettings): ShellExecutionMode
    fun update(settings: StoredAppSettings, mode: ShellExecutionMode): StoredAppSettings
}

fun interface ShellModePolicy {
    val settings: ShellModeSettingsPolicy? get() = null
    suspend fun mode(): ShellExecutionMode
}

class KcodeShellMode(ctx: Context, val policy: ShellModePolicy) : Service<Unit>(ctx, Key) {
    companion object { val Key = ServiceKey<KcodeShellMode>("shellMode") }
}
