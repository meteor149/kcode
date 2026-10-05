package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.PluginCompositionStore

data class PreparedProfileBootstrap(
    val definition: ProfileDefinition,
    val session: ProfileCompositionSession,
    val migrating: Boolean,
)

/** Reads legacy state without rewriting it; migration commits only after successful activation. */
suspend fun prepareProfileBootstrap(
    repository: ProfileRepository,
    template: ProfileDefinition,
    bundles: List<ProfileBundle>,
    legacyStore: PluginCompositionStore? = null,
    aliases: Map<String, Set<String>> = emptyMap(),
    requestedId: String? = null,
    machineConfiguredPackages: Set<String> = emptySet(),
): PreparedProfileBootstrap {
    val selected = repository.selected()
    val id = requestedId ?: selected ?: template.id
    val committed = repository.loadCommitted(id)
    if (committed != null) return PreparedProfileBootstrap(
        committed.definition, ProfileCompositionSession.open(repository, committed.definition, initialBundles = bundles.filter { bundle -> committed.definition.bundles.any { it.id == bundle.id } }), migrating = false,
    )
    val draft = repository.loadDraft(id)
    if (draft != null) {
        // A previous migration may have saved its draft and then failed activation.
        // Keep legacy installation state until the first generation actually commits.
        val legacy = if (id == template.id) legacyStore?.load() else null
        return PreparedProfileBootstrap(
            draft, ProfileCompositionSession.open(repository, draft, legacy ?: PluginCompositionSnapshot(), bundles.filter { bundle -> draft.bundles.any { it.id == bundle.id } }),
            migrating = legacy != null,
        )
    }
    require(id == template.id) { "Selected profile has no restorable definition" }
    val legacy = legacyStore?.load() ?: PluginCompositionSnapshot()
    val migrated = migrateLegacyProfile(template, bundles, legacy, aliases, machineConfiguredPackages)
    repository.saveDraft(migrated)
    return PreparedProfileBootstrap(
        migrated, ProfileCompositionSession.open(repository, migrated, legacy, bundles.filter { bundle -> migrated.bundles.any { it.id == bundle.id } }), migrating = legacyStore != null,
    )
}
