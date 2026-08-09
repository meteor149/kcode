@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.setting


import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.modelOption
import ai.meteor.kcode.model.modelsFor
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.search.WebSearchProvider
import ai.meteor.kcode.ui.component.BottomSheetOverlay
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
@Composable
internal fun SettingsPageOverlay(
    current: ModelConfiguration?,
    appSettings: StoredAppSettings,
    persistenceFailure: PersistenceFailure?,
    shellSettingsAvailable: Boolean,
    onSettingsChange: (StoredAppSettings) -> Unit,
    onModelSettingsChange: (ModelConfiguration, Map<String, String>) -> Unit,
    onShellExecutionModeChanged: (ShellExecutionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsDialog(
        current = current,
        storedProvider = ModelProvider.entries.firstOrNull { it.name == appSettings.provider }
            ?: ModelProvider.OpenAI,
        modelApiKeys = appSettings.modelApiKeys,
        dashscopeRegion = DashscopeRegion.fromCode(appSettings.dashscopeRegion),
        persistenceFailure = persistenceFailure,
        language = AppLanguage.fromCode(appSettings.language),
        shellSettingsAvailable = shellSettingsAvailable,
        shellExecutionMode = ShellExecutionMode.fromCode(appSettings.shellExecutionMode),
        webSearchApiKey = appSettings.webSearchApiKey,
        exaSearchApiKey = appSettings.exaSearchApiKey,
        webSearchProvider = WebSearchProvider.fromCode(appSettings.webSearchProvider),
        onLanguageChange = { onSettingsChange(appSettings.copy(language = it.code)) },
        onShellExecutionModeChange = {
            onShellExecutionModeChanged(it)
            onSettingsChange(appSettings.copy(shellExecutionMode = it.code))
        },
        onWebSearchSettingsSave = { provider, brightDataKey, exaKey ->
            onSettingsChange(
                appSettings.copy(
                    webSearchProvider = provider.code,
                    webSearchApiKey = brightDataKey,
                    exaSearchApiKey = exaKey,
                ),
            )
        },
        onSave = { saved, apiKeys ->
            val provider = saved.provider
            val existingModel = current?.takeIf { it.provider == provider }
                ?.let { modelOption(provider, it.modelId) }
            val model = existingModel ?: modelsFor(provider).first()
            onModelSettingsChange(
                saved.copy(
                    modelId = model.id,
                    temperature = if (existingModel != null) {
                        current.temperature
                    } else {
                        model.defaultTemperature
                    },
                ),
                apiKeys,
            )
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun SettingsDialog(
    current: ModelConfiguration?,
    storedProvider: ModelProvider,
    modelApiKeys: Map<String, String>,
    dashscopeRegion: DashscopeRegion,
    persistenceFailure: PersistenceFailure?,
    language: AppLanguage,
    shellSettingsAvailable: Boolean,
    shellExecutionMode: ShellExecutionMode,
    webSearchApiKey: String,
    exaSearchApiKey: String,
    webSearchProvider: WebSearchProvider,
    onLanguageChange: (AppLanguage) -> Unit,
    onShellExecutionModeChange: (ShellExecutionMode) -> Unit,
    onWebSearchSettingsSave: (WebSearchProvider, String, String) -> Unit,
    onSave: (ModelConfiguration, Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var route by rememberSaveable(stateSaver = SettingsRouteSaver) {
        mutableStateOf(SettingsRoute.Home)
    }
    var provider by remember(current, storedProvider) {
        mutableStateOf(current?.provider ?: storedProvider)
    }
    var apiKeys by remember(modelApiKeys, current) {
        mutableStateOf(
            if (current == null || current.apiKey.isBlank()) {
                modelApiKeys
            } else {
                modelApiKeys + (current.provider.name to current.apiKey)
            },
        )
    }
    var endpoint by remember(current) { mutableStateOf(current?.endpoint.orEmpty()) }
    var region by remember(current) { mutableStateOf(current?.region.orEmpty()) }
    var deployment by remember(current) { mutableStateOf(current?.deployment.orEmpty()) }
    var apiVersion by remember(current) { mutableStateOf(current?.apiVersion.orEmpty()) }
    var selectedDashscopeRegion by remember(dashscopeRegion) { mutableStateOf(dashscopeRegion) }
    var showKey by remember { mutableStateOf(false) }
    var searchApiKey by remember(webSearchApiKey) { mutableStateOf(webSearchApiKey) }
    var exaApiKey by remember(exaSearchApiKey) { mutableStateOf(exaSearchApiKey) }
    var searchProvider by remember(webSearchProvider) { mutableStateOf(webSearchProvider) }
    var showSearchKey by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(route) {
        focusManager.clearFocus(force = true)
    }

    BottomSheetOverlay(
        onDismissRequest = onDismiss,
        onBackRequest = if (route == SettingsRoute.Home) null else {
            { route = SettingsRoute.Home }
        },
    ) { dismissSheet ->
        Column(Modifier.fillMaxSize()) {
            SettingsWindowHeader(
                title = when (route) {
                    SettingsRoute.Home -> text(UiText.Settings)
                    SettingsRoute.Language -> text(UiText.Language)
                    SettingsRoute.ModelService -> text(UiText.ModelService)
                    SettingsRoute.ShellExecution -> text(UiText.ShellExecution)
                    SettingsRoute.InternetSearch -> text(UiText.InternetSearch)
                },
                isRoot = route == SettingsRoute.Home,
                onNavigation = {
                    if (route == SettingsRoute.Home) dismissSheet() else route = SettingsRoute.Home
                },
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (route) {
                    SettingsRoute.Home -> SettingsHome(
                        language = language,
                        current = current,
                        onLanguage = { route = SettingsRoute.Language },
                        onModelService = { route = SettingsRoute.ModelService },
                        webSearchProvider = webSearchProvider,
                        onInternetSearch = { route = SettingsRoute.InternetSearch },
                        shellSettingsAvailable = shellSettingsAvailable,
                        shellExecutionMode = shellExecutionMode,
                        onShellExecution = { route = SettingsRoute.ShellExecution },
                    )

                    SettingsRoute.Language -> LanguageSettings(
                        language = language,
                        onLanguageChange = onLanguageChange,
                    )

                    SettingsRoute.ModelService -> ModelServiceSettings(
                        provider = provider,
                        apiKey = apiKeys[provider.name].orEmpty(),
                        dashscopeRegion = selectedDashscopeRegion,
                        endpoint = endpoint,
                        region = region,
                        deployment = deployment,
                        apiVersion = apiVersion,
                        showKey = showKey,
                        persistenceFailure = persistenceFailure,
                        onProviderChange = {
                            provider = it
                            if (current?.provider != it) {
                                endpoint = if (it == ModelProvider.Ollama) "http://localhost:11434" else ""
                                region = if (it == ModelProvider.Bedrock) "us-west-2" else ""
                                deployment = ""
                                apiVersion = if (it == ModelProvider.AzureOpenAI) "2024-10-21" else ""
                            }
                        },
                        onApiKeyChange = { apiKey ->
                            apiKeys = apiKeys + (provider.name to apiKey)
                        },
                        onDashscopeRegionChange = { selectedDashscopeRegion = it },
                        onEndpointChange = { endpoint = it },
                        onRegionChange = { region = it },
                        onDeploymentChange = { deployment = it },
                        onApiVersionChange = { apiVersion = it },
                        onToggleKey = { showKey = !showKey },
                        onSave = {
                            val model = modelsFor(provider).first()
                            onSave(ModelConfiguration(
                                provider = provider,
                                modelId = model.id,
                                apiKey = apiKeys[provider.name].orEmpty().trim(),
                                temperature = model.defaultTemperature,
                                endpoint = endpoint.trim(),
                                region = region.trim(),
                                deployment = deployment.trim(),
                                apiVersion = apiVersion.trim(),
                                dashscopeRegion = selectedDashscopeRegion,
                            ), apiKeys.mapValues { it.value.trim() })
                            route = SettingsRoute.Home
                        },
                    )

                    SettingsRoute.ShellExecution -> ShellExecutionSettings(
                        selected = shellExecutionMode,
                        onSelected = onShellExecutionModeChange,
                    )

                    SettingsRoute.InternetSearch -> InternetSearchSettings(
                        provider = searchProvider,
                        brightDataApiKey = searchApiKey,
                        exaApiKey = exaApiKey,
                        showKey = showSearchKey,
                        onProviderChange = { searchProvider = it },
                        onBrightDataApiKeyChange = { searchApiKey = it },
                        onExaApiKeyChange = { exaApiKey = it },
                        onToggleKey = { showSearchKey = !showSearchKey },
                        onSave = {
                            onWebSearchSettingsSave(searchProvider, searchApiKey.trim(), exaApiKey.trim())
                            route = SettingsRoute.Home
                        },
                    )
                }
            }
        }
    }
}

private enum class SettingsRoute { Home, Language, ModelService, ShellExecution, InternetSearch }

private val SettingsRouteSaver = Saver<SettingsRoute, String>(
    save = { it.name },
    restore = SettingsRoute::valueOf,
)
