package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition

/** A verified composition and its durable publisher; neither allocates provider instances. */
data class ProfileActivation(
    val resolved: ResolvedProfile,
    val session: ProfileCompositionSession,
    val machineConfiguration: ((ProfileDefinition, List<ProfileBundle>) -> List<ProfileOperation>)? = null,
)
