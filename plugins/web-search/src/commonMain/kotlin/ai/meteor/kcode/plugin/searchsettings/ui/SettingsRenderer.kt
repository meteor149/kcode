package ai.meteor.kcode.plugin.searchsettings.ui

import ai.meteor.kcode.plugin.ui.api.SettingsSectionRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.tools.search.SearchSettingsConfiguration
import ai.meteor.kcode.tools.search.SearchSettingsPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

internal class SearchSettingsRenderer(private val policy: SearchSettingsPolicy) : UiRenderer<SettingsSectionRequest> {
    @Composable
    override fun Render(request: SettingsSectionRequest) {
        val providers = policy.providers() ?: return
        if (providers.isEmpty()) return
        val settings = request.page.appSettings
        val configuration = policy.resolve(settings)
        var searchProvider by remember(policy, configuration.provider) { mutableStateOf(configuration.provider) }
        var apiKeys by remember(policy, configuration.apiKeys) { mutableStateOf(configuration.apiKeys) }
        var showSearchKey by remember { mutableStateOf(false) }
        val selected = providers.firstOrNull { it.id == searchProvider } ?: return
        InternetSearchSettings(
            providers = providers,
            provider = selected,
            apiKey = apiKeys[selected.id].orEmpty(),
            showKey = showSearchKey,
            onProviderChange = { searchProvider = it.id },
            onApiKeyChange = { apiKeys = apiKeys + (selected.id to it) },
            onToggleKey = { showSearchKey = !showSearchKey },
            onSave = {
                request.page.onSettingsChange(policy.update(settings, SearchSettingsConfiguration(
                    searchProvider,
                    if (selected.requiresApiKey) apiKeys + (selected.id to apiKeys[selected.id].orEmpty().trim())
                    else apiKeys,
                )))
                request.onReturn()
            },
        )
    }
}