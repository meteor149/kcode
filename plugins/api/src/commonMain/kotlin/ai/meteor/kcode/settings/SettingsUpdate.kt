package ai.meteor.kcode.settings

data class SettingsUpdate(
    val modelProvider: String? = null,
    val model: String? = null,
    val modelApiKey: String? = null,
    val modelEndpoint: String? = null,
    val modelRegion: String? = null,
    val modelDeployment: String? = null,
    val modelApiVersion: String? = null,
    val dashscopeRegion: String? = null,
    val temperature: String? = null,
    val searchProvider: String? = null,
    val searchApiKey: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(
            modelProvider,
            model,
            modelApiKey,
            modelEndpoint,
            modelRegion,
            modelDeployment,
            modelApiVersion,
            dashscopeRegion,
            temperature,
            searchProvider,
            searchApiKey,
        ).all { it == null }
}

data class AppliedSettingsUpdate(
    val settings: StoredAppSettings,
    val changedFields: List<String>,
)
