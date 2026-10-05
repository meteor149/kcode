package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget

data class ProfileIntent(val definition: ProfileDefinition, val base: CommittedProfileGeneration?)

suspend fun loadProfileIntent(repository: ProfileGenerationRepository, target: ProfileTarget): ProfileIntent {
    target.validate()
    return when (target.source) {
        ProfileSource.Committed -> requireNotNull(repository.loadCommitted(target.profileId)) { "Profile has no committed generation" }
            .let { ProfileIntent(it.definition, it) }
        ProfileSource.Draft -> requireNotNull(repository.loadDraftDocument(target.profileId)) { "Profile has no draft" }
            .let { ProfileIntent(it.definition, it.base ?: repository.loadCommitted(target.profileId)) }
        ProfileSource.History -> requireNotNull(repository.loadGeneration(target.profileId, checkNotNull(target.generation))) { "Historical generation is missing" }
            .let { ProfileIntent(it.definition, it) }
    }
}

fun profileIntentBundles(intent: ProfileIntent, catalogue: List<ProfileBundle>): List<ProfileBundle> =
    intent.definition.bundles.map { reference ->
        intent.base?.bundles?.firstOrNull { it.id == reference.id && it.version == reference.version }
            ?: requireNotNull(catalogue.firstOrNull { it.id == reference.id && it.version == reference.version }) {
                "Missing Profile bundle '${reference.id}@${reference.version}'"
            }
    }
