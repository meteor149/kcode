package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.StoredPluginConfiguration
import org.cordis.loader.EntryOptions

/** Host deployment addresses are machine overlays, never portable Profile intent. */
fun profileMachineConfiguration(
    definition: ProfileDefinition,
    bundles: List<ProfileBundle>,
    configurations: Map<String, StoredPluginConfiguration>,
): List<ProfileOperation> {
    val operations = mutableListOf<ProfileOperation>()
    fun visit(entries: List<EntryOptions>) {
        entries.forEach { entry ->
            configurations[entry.name]?.let { configuration ->
                operations += ProfileOperation.Configure(entry.id, configuration.value, configuration.kind)
            }
            if (entry.group == true) visit((entry.config as List<*>).map { it as EntryOptions })
        }
    }
    visit(ProfileCompiler().compile(definition, bundles).requireValid().entries)
    return operations
}

/** Logical names distinguish per-profile storage from explicitly shared scopes. */
fun profileDataScopeKey(profileId: String, scope: String): String {
    ProfileDefinition(id = profileId, dataScope = ProfileDataScope(settings = scope)).validate()
    return when (scope) {
        "legacy" -> "legacy"
        "profile" -> "profile-$profileId"
        else -> "shared-$scope"
    }
}
