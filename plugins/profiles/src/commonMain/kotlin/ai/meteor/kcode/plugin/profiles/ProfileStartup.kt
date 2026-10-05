package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
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
    builtinOverrides: Set<String> = emptySet(),
    stageSwitch: Boolean = false,
    activationRequest: ProfileActivationRequest? = null,
): ProfileActivation {
    val switchRevision = if (stageSwitch) {
        require(requestedId != null) { "A staged switch requires an explicit target Profile" }
        requireNotNull(repository as? ProfileGenerationRepository) { "Repository does not support atomic Profile switching" }.state().revision
    } else null
    require(activationRequest == null || stageSwitch && activationRequest.target.profileId == requestedId) { "Explicit activation requires a staged matching target" }
    require(activationRequest == null || activationRequest.expectedRevision == switchRevision) { "Profile repository changed; refresh before activating" }
    val intent = activationRequest?.let {
        loadProfileIntent(repository as ProfileGenerationRepository, it.target)
    }
    val prepared = if (intent == null) prepareProfileBootstrap(repository, template, bundles, legacyStore, aliases, requestedId, machineConfiguredPackages, switchRevision)
        else PreparedProfileBootstrap(intent.definition, ProfileCompositionSession.prepareCandidate(
            repository as ProfileGenerationRepository, intent.definition, checkNotNull(switchRevision),
            intent.base?.composition ?: ai.meteor.kcode.plugin.api.PluginCompositionSnapshot(), profileIntentBundles(intent, bundles)), false)
    val committed = intent?.base ?: repository.loadCommitted(prepared.definition.id)
        ?: (repository as? ProfileGenerationRepository)?.loadDraftDocument(prepared.definition.id)?.base
    val frozenBundles = if (intent != null) profileIntentBundles(intent, bundles) else committed?.bundles?.takeIf { it.isNotEmpty() } ?: bundles
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
        machineOverrides(prepared.definition, frozenBundles), launchOverrides, builtinOverrides,
    )
    return ProfileActivation(resolved, prepared.session, machineOverrides)
}
