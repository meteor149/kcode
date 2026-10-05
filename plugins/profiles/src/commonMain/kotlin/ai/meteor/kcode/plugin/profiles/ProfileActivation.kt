package ai.meteor.kcode.plugin.profiles

/** A verified composition and its durable publisher; neither allocates provider instances. */
data class ProfileActivation(val resolved: ResolvedProfile, val session: ProfileCompositionSession)
