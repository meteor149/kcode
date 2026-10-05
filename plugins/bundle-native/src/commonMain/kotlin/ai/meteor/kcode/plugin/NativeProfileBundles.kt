package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation

/** Shipped product layers are data; the module catalogue remains independent of selection. */
fun nativeProfileBundles(moduleIds: List<String>): List<ProfileBundle> {
    require(moduleIds.distinct().size == moduleIds.size && moduleIds.all { it.isNotBlank() })
    val layers = listOf("kcode.base", "kcode.agent", "kcode.default-ui")
    fun layer(id: String) = when {
        id.startsWith("provider.ui.") || id in setOf("feature.markdown", "core.ui-slots") -> "kcode.default-ui"
        id.startsWith("core.") || id in setOf("provider.settings.platform", "provider.history.platform", "feature.localization") -> "kcode.base"
        else -> "kcode.agent"
    }
    return layers.map { name ->
        ProfileBundle(id = name, version = "1", patches = listOf(ProfileOperation.Insert(
            moduleIds.filter { layer(it) == name }.map { ProfileEntry(it, it) },
        )))
    }
}

fun nativeProfileTemplate(bundles: List<ProfileBundle>, includeDefaults: Boolean = true) = ProfileDefinition(
    id = "native",
    bundles = if (includeDefaults) bundles.map { ProfileBundleReference(it.id, it.version) } else emptyList(),
    // Migration preserves the former native data locations. New user profiles default to isolation.
    dataScope = ProfileDataScope(settings = "legacy", history = "legacy"),
)

val NativeProfileInfrastructureAliases = mapOf(
    "provider.plugin-installations.platform" to emptySet<String>(),
    "provider.plugin-packages.platform" to emptySet(),
)
