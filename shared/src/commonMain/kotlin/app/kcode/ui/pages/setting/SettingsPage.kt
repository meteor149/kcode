@file:OptIn(ExperimentalMaterial3Api::class)

package app.kcode.ui.pages.setting


import app.kcode.model.ModelConfiguration
import app.kcode.model.ModelProvider
import app.kcode.model.modelOption
import app.kcode.model.modelsFor
import app.kcode.settings.ShellExecutionMode
import app.kcode.settings.StoredAppSettings
import app.kcode.tools.search.WebSearchProvider
import app.kcode.ui.component.BottomSheetOverlay
import app.kcode.localization.AppLanguage
import app.kcode.localization.UiText
import app.kcode.localization.text
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
    onConfigurationChange: (ModelConfiguration) -> Unit,
    onShellExecutionModeChanged: (ShellExecutionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsDialog(
        current = current,
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
        onSave = { saved ->
            val provider = saved.provider
            val existingModel = current?.modelId
                ?.let(::modelOption)
                ?.takeIf { it.provider == provider }
            val model = existingModel ?: modelsFor(provider).first()
            onConfigurationChange(
                saved.copy(
                    modelId = model.id,
                    temperature = if (existingModel != null) {
                        current.temperature
                    } else {
                        model.defaultTemperature
                    },
                ),
            )
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun SettingsDialog(
    current: ModelConfiguration?,
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
    onSave: (ModelConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    var route by remember { mutableStateOf(SettingsRoute.Home) }
    var provider by remember(current) { mutableStateOf(current?.provider ?: ModelProvider.OpenAI) }
    var apiKey by remember(current) { mutableStateOf(current?.apiKey.orEmpty()) }
    var endpoint by remember(current) { mutableStateOf(current?.endpoint.orEmpty()) }
    var region by remember(current) { mutableStateOf(current?.region.orEmpty()) }
    var deployment by remember(current) { mutableStateOf(current?.deployment.orEmpty()) }
    var apiVersion by remember(current) { mutableStateOf(current?.apiVersion.orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var searchApiKey by remember(webSearchApiKey) { mutableStateOf(webSearchApiKey) }
    var exaApiKey by remember(exaSearchApiKey) { mutableStateOf(exaSearchApiKey) }
    var searchProvider by remember(webSearchProvider) { mutableStateOf(webSearchProvider) }
    var showSearchKey by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(route) {
        focusManager.clearFocus(force = true)
    }

    BottomSheetOverlay(onDismissRequest = onDismiss) { dismissSheet ->
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
                        apiKey = apiKey,
                        endpoint = endpoint,
                        region = region,
                        deployment = deployment,
                        apiVersion = apiVersion,
                        showKey = showKey,
                        persistenceFailure = persistenceFailure,
                        onProviderChange = {
                            provider = it
                            if (current?.provider != it) {
                                apiKey = ""
                                endpoint = if (it == ModelProvider.Ollama) "http://localhost:11434" else ""
                                region = if (it == ModelProvider.Bedrock) "us-west-2" else ""
                                deployment = ""
                                apiVersion = if (it == ModelProvider.AzureOpenAI) "2024-10-21" else ""
                            }
                        },
                        onApiKeyChange = { apiKey = it },
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
                                apiKey = apiKey.trim(),
                                temperature = model.defaultTemperature,
                                endpoint = endpoint.trim(),
                                region = region.trim(),
                                deployment = deployment.trim(),
                                apiVersion = apiVersion.trim(),
                            ))
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
