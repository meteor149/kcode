package ai.meteor.kcode.model

data class ModelConnectionRequirements(
    val apiKey: Boolean = true,
    val endpoint: Boolean = false,
    val region: Boolean = false,
    val deployment: Boolean = false,
    /** Historical flag; new providers declare generic region choices in their catalog. */
    val dashscopeRegions: Boolean = false,
)

data class ModelConnectionDefaults(
    val endpoint: String = "",
    val region: String = "",
    val deployment: String = "",
    val apiVersion: String = "",
)

/** A provider-defined connection choice; UI uses registered labels, never a vendor switch. */
data class ModelConnectionChoice(
    val value: String,
    val displayNames: Map<String, String> = emptyMap(),
)

/** Metadata and models belong to a reversible adapter contribution, not an implicit UI inventory. */
data class ModelProviderSpec(
    val provider: ModelProvider,
    val models: List<ModelOption>,
    val order: Int,
    val requirements: ModelConnectionRequirements = ModelConnectionRequirements(),
    val defaults: ModelConnectionDefaults = ModelConnectionDefaults(),
    val displayName: String? = null,
    val description: String? = null,
    val displayNames: Map<String, String> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
    /** Optional default-UI icon identity. Unknown identities use the generic model icon. */
    val iconId: String? = null,
    val regionChoices: List<ModelConnectionChoice> = emptyList(),
    /** Read-only historical field used when the generic region has not been saved. */
    val regionMigrationKey: String? = null,
)

data class ModelCatalogSnapshot(val providers: List<ModelProviderSpec> = emptyList()) {
    fun provider(provider: ModelProvider): ModelProviderSpec? = providers.firstOrNull { it.provider == provider }
    fun modelsFor(provider: ModelProvider): List<ModelOption> = provider(provider)?.models.orEmpty()
    fun modelOption(provider: ModelProvider, id: String?): ModelOption? = modelsFor(provider).firstOrNull { it.id == id }
}
