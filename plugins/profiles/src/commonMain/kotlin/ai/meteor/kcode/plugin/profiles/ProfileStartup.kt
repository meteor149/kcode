package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.KcodePluginMount
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.PluginCompositionStore

/** Native hosts prepare deployments; only the managed runtime allocates product instances. */
fun interface ProfileStartupFactory {
    suspend fun prepare(modules: List<KcodePluginMount>): ProfileActivation
}

suspend fun prepareNativeProfileActivation(
    repository: ProfileRepository,
    template: ProfileDefinition,
    bundles: List<ProfileBundle>,
    resolver: PluginPackageResolver,
    offers: Map<String, ProfilePackageOffer>,
    builtinModules: Set<String>,
    legacyStore: PluginCompositionStore? = null,
    aliases: Map<String, Set<String>> = emptyMap(),
    requestedId: String? = null,
    machineOverrides: (ProfileDefinition, List<ProfileBundle>) -> List<ProfileOperation> = { _, _ -> emptyList() },
    launchOverrides: List<ProfileOperation> = emptyList(),
    machineConfiguredPackages: Set<String> = emptySet(),
): ProfileActivation {
    val prepared = prepareProfileBootstrap(repository, template, bundles, legacyStore, aliases, requestedId, machineConfiguredPackages)
    val committed = repository.loadCommitted(prepared.definition.id)
    val frozenBundles = committed?.bundles?.takeIf { it.isNotEmpty() } ?: bundles
    val snapshot = prepared.session.load()
    val previous = snapshot.external.map { it.toSpec() }
    // Restart an existing generation from its lock. Newly introduced distro offers do not upgrade it.
    val effectiveOffers = if (committed != null) offers.filterKeys { id -> previous.none { it.id == id } }
        else offers.filterKeys { id ->
            val existing = previous.firstOrNull { it.id == id }
            existing == null || existing.packageInstallation?.archiveSha256 == snapshot.bundledPackages[id]
        }
    val resolved = ProfileResolver(resolver).resolve(
        prepared.definition, frozenBundles, effectiveOffers, builtinModules, previous,
        machineOverrides(prepared.definition, frozenBundles), launchOverrides,
    )
    return ProfileActivation(resolved, prepared.session)
}
