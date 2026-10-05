package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.ToolPermissionSettingsPolicy
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ToolPermissionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class StoredToolPermissionSettingsPolicy : ToolPermissionSettingsPolicy {
    private val live = MutableStateFlow(true)
    fun close() { live.value = false }

    override fun resolve(settings: StoredAppSettings): ToolPermissionMode {
        check(live.value) { "Tool permission settings policy has been disposed" }
        val document = settings.namespaces[Namespace]
        val code = (if (document == null) settings.legacyValues["toolPermissionMode"] else document["mode"])?.let { value ->
            require(value is JsonPrimitive && value.isString) { "Tool permission mode must be a string" }
            value.content
        }.orEmpty()
        return ToolPermissionMode.fromCode(code) ?: ToolPermissionMode.Ask
    }

    override fun update(settings: StoredAppSettings, mode: ToolPermissionMode): StoredAppSettings {
        check(live.value) { "Tool permission settings policy has been disposed" }
        val document = JsonObject(settings.namespaces[Namespace].orEmpty() + ("mode" to JsonPrimitive(mode.code)))
        return settings.copy(namespaces = settings.namespaces + (Namespace to document))
    }

    private companion object { const val Namespace = "feature.interaction-settings" }
}
