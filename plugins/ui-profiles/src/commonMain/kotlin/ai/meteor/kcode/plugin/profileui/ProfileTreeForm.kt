package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal enum class ProfileTreeAction { Insert, Group, Configure, Move, Replace, Enable, Disable, Remove, Context }

/** Validate form inputs without applying plugins or independently composing a tree. */
internal data class ProfileTreeForm(
    val action: ProfileTreeAction,
    val target: String = "",
    val id: String = "",
    val module: String = "",
    val parent: String? = null,
    val position: String = "",
    val configuration: String = "",
    val configurationKind: String = "json",
    val inject: String = "{}",
    val intercept: String = "{}",
    val isolate: String = "{}",
) {
    fun operation(entries: List<ProfileEntry>, modules: Set<String>): ProfileOperation {
        val flat = flattenProfileEntries(entries)
        val selected = flat.singleOrNull { it.id == target }
        fun requireTarget() = requireNotNull(selected) { "Select an existing entry" }
        fun checkedParent() {
            require(parent == null || flat.any { it.id == parent && it.children != null }) { "Select a group parent" }
        }
        fun checkedPosition(): Int? = position.takeIf { it.isNotBlank() }?.let {
            requireNotNull(it.toIntOrNull()) { "Position must be an integer" }
        }
        fun checkedModule() {
            require(module in modules) { "Select an available module" }
        }
        return when (action) {
            ProfileTreeAction.Insert, ProfileTreeAction.Group -> {
                require(id.isNotBlank() && id == id.trim() && flat.none { it.id == id }) { "Entry identity must be new and nonempty" }
                checkedParent()
                if (action == ProfileTreeAction.Insert) checkedModule()
                val value = if (action == ProfileTreeAction.Group) ProfileEntry(id, "core.group", children = emptyList())
                    else if (configuration.isBlank() && configurationKind !in listOf("unit", "null")) ProfileEntry(id, module)
                    else ProfileEntry(id, module, configValue(), configurationKind = configurationKind)
                ProfileOperation.Insert(listOf(value), parent, checkedPosition())
            }
            ProfileTreeAction.Configure -> {
                require(requireTarget().children == null) { "A group uses tree operations rather than plugin configuration" }
                ProfileOperation.Configure(target, configValue(), configurationKind)
            }
            ProfileTreeAction.Move -> {
                val entry = requireTarget()
                checkedParent()
                require(parent != entry.id && flattenProfileEntries(entry.children.orEmpty()).none { it.id == parent }) {
                    "Entry cannot move into its descendants"
                }
                ProfileOperation.Move(target, parent, checkedPosition())
            }
            ProfileTreeAction.Replace -> {
                require(requireTarget().children == null) { "A group cannot select a plugin module" }
                checkedModule()
                ProfileOperation.Replace(target, module, selected!!.packageId)
            }
            ProfileTreeAction.Enable -> { requireTarget(); ProfileOperation.Enable(target) }
            ProfileTreeAction.Disable -> { requireTarget(); ProfileOperation.Disable(target) }
            ProfileTreeAction.Remove -> { requireTarget(); ProfileOperation.Remove(target) }
            ProfileTreeAction.Context -> {
                requireTarget()
                val isolation = objectValue(isolate).mapValues { (_, value) ->
                    if (value == JsonNull) null else {
                        val text = requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
                        require(text.isNotBlank()) { "Shared realms must be nonempty" }
                        text
                    }
                }
                ProfileOperation.Context(target, objectValue(inject), objectValue(intercept), isolation)
            }
        }
    }

    private fun objectValue(text: String): Map<String, JsonElement> {
        val value = Json.parseToJsonElement(text) as? JsonObject ?: error("Context must be a JSON object")
        require(value.keys.all { it.isNotBlank() }) { "Context service identities must be nonempty" }
        return value.toMap()
    }

    private fun configValue(): JsonElement {
        require(configurationKind in ProfileConfigurationKinds) { "Unknown configuration codec" }
        val value = if (configurationKind == "unit" || configurationKind == "null") JsonNull
            else Json.parseToJsonElement(configuration)
        StoredPluginConfiguration(configurationKind, value).decode()
        return value
    }
}

internal val ProfileConfigurationKinds = listOf("json", "string", "boolean", "int", "long", "float", "double", "unit", "null")

internal fun flattenProfileEntries(entries: List<ProfileEntry>): List<ProfileEntry> = buildList {
    entries.forEach { entry -> add(entry); addAll(flattenProfileEntries(entry.children.orEmpty())) }
}
