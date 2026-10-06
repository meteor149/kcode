package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget

/** Imported documents locate code only by locked digest in the host's verified cache. */
suspend fun profileImportedOffers(
    repository: ProfileGenerationRepository,
    id: String,
    target: ProfileTarget?,
    offers: Map<String, ProfilePackageOffer>,
    cached: (String) -> PluginPackageImport,
): Map<String, ProfilePackageOffer> {
    if (target != null && target.source != ProfileSource.Draft || target == null && repository.loadCommitted(id) != null) return offers
    val draft = repository.loadDraftDocument(id) ?: return offers
    val imported = draft.imported.takeIf { draft.base == null }?.lock?.packages.orEmpty()
    return offers + (imported + draft.packageImports.packages).associate { locked ->
        locked.id to ProfilePackageOffer(cached(locked.archiveSha256), locked.dependencies.keys)
    }
}
