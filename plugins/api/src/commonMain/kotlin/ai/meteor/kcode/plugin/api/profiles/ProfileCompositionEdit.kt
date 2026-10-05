package ai.meteor.kcode.plugin.api.profiles

/** Compare against the active identity and durable generation before changing any instance. */
data class ProfileCompositionEdit(
    val profileId: String,
    val expectedGeneration: Long,
    val operations: List<ProfileOperation>,
)

/** Portable committed intent; resolved paths, code exports and resources are never exposed. */
data class ProfileCompositionState(
    val definition: ProfileDefinition,
    val generation: Long,
)
