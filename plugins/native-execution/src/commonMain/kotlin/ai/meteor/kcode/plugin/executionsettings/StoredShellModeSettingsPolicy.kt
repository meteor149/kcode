package ai.meteor.kcode.plugin.executionsettings

import ai.meteor.kcode.plugin.api.ShellModeSettingsPolicy
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class StoredShellModeSettingsPolicy : ShellModeSettingsPolicy {
    private val live = MutableStateFlow(true)
    val isActive get() = live.value
    fun close() { live.value = false }

    override fun resolve(settings: StoredAppSettings): ShellExecutionMode {
        check(live.value) { "Shell mode settings policy has been disposed" }
        val document = settings.namespaces[Namespace]
        val code = (if (document == null) settings.legacyValues["shellExecutionMode"] else document["mode"])?.let { value ->
            require(value is JsonPrimitive && value.isString) { "Shell mode must be a string" }
            value.content
        }.orEmpty()
        return ShellExecutionMode.fromCode(code) ?: ShellExecutionMode.App
    }

    override fun update(settings: StoredAppSettings, mode: ShellExecutionMode): StoredAppSettings {
        check(live.value) { "Shell mode settings policy has been disposed" }
        val document = JsonObject(settings.namespaces[Namespace].orEmpty() + ("mode" to JsonPrimitive(mode.code)))
        return settings.copy(namespaces = settings.namespaces + (Namespace to document))
    }

    private companion object {
        const val Namespace = "feature.execution-settings"
    }
}
