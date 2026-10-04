package ai.meteor.kcode.model

data class ModelConnectionRequirements(
    val apiKey: Boolean = true,
    val endpoint: Boolean = false,
    val region: Boolean = false,
    val deployment: Boolean = false,
    val dashscopeRegions: Boolean = false,
)

data class ModelConnectionDefaults(
    val endpoint: String = "",
    val region: String = "",
    val deployment: String = "",
    val apiVersion: String = "",
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
)

data class ModelCatalogSnapshot(val providers: List<ModelProviderSpec> = emptyList()) {
    fun provider(provider: ModelProvider): ModelProviderSpec? = providers.firstOrNull { it.provider == provider }
    fun modelsFor(provider: ModelProvider): List<ModelOption> = provider(provider)?.models.orEmpty()
    fun modelOption(provider: ModelProvider, id: String?): ModelOption? = modelsFor(provider).firstOrNull { it.id == id }
}
