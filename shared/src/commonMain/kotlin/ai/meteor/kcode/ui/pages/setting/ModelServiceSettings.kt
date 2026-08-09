@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.setting

import ai.meteor.kcode.ui.design.Mist
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Error

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.requiresApiKey
import ai.meteor.kcode.model.requiresDeployment
import ai.meteor.kcode.model.requiresEndpoint
import ai.meteor.kcode.model.requiresRegion
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.meteor.kcode.ui.component.ApiKeyField
@Composable
internal fun ModelServiceSettings(
    provider: ModelProvider,
    apiKey: String,
    endpoint: String,
    region: String,
    deployment: String,
    apiVersion: String,
    showKey: Boolean,
    persistenceFailure: PersistenceFailure?,
    onProviderChange: (ModelProvider) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onRegionChange: (String) -> Unit,
    onDeploymentChange: (String) -> Unit,
    onApiVersionChange: (String) -> Unit,
    onToggleKey: () -> Unit,
    onSave: () -> Unit,
) {
    val saveInteraction = remember { MutableInteractionSource() }
    val saveEnabled = (!provider.requiresApiKey || apiKey.isNotBlank()) &&
        (!provider.requiresEndpoint || endpoint.isNotBlank()) &&
        (!provider.requiresRegion || region.isNotBlank()) &&
        (!provider.requiresDeployment || deployment.isNotBlank())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSectionLabel(text(UiText.Provider))
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            ModelProvider.entries.forEachIndexed { index, item ->
                CompactProviderRow(item, provider == item) { onProviderChange(item) }
                if (index != ModelProvider.entries.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
                }
            }
        }
        if (provider.requiresApiKey) {
            CompactSectionLabel(text(UiText.ApiKey), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ApiKeyField(
                    apiKey,
                    showKey,
                    onApiKeyChange,
                    onToggleKey
                )
            }
        }
        if (provider.requiresEndpoint) {
            CompactSectionLabel(text(UiText.Endpoint), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(
                    value = endpoint,
                    placeholder = if (provider == ModelProvider.Ollama) "http://localhost:11434" else "https://…openai.azure.com",
                    onValueChange = onEndpointChange,
                )
            }
        }
        if (provider.requiresDeployment) {
            CompactSectionLabel(text(UiText.Deployment), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(deployment, text(UiText.DeploymentHint), onDeploymentChange)
            }
            CompactSectionLabel(text(UiText.ApiVersion), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(apiVersion, "2024-10-21", onApiVersionChange)
            }
        }
        if (provider.requiresRegion) {
            CompactSectionLabel(text(UiText.Region), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(region, "us-west-2", onRegionChange)
            }
        }
        persistenceFailure?.let {
            val detail = it.detail ?: text(UiText.UnknownError)
            Text(
                text(if (it.reading) UiText.ReadSettingsFailed else UiText.SaveSettingsFailed, detail),
                Modifier.padding(start = KcodeSpacing.xs, top = KcodeSpacing.sm, end = KcodeSpacing.xs),
                color = Error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.fillMaxWidth().padding(top = KcodeSpacing.md)
                .pressScale(saveInteraction, PressScaleStyle.Button, saveEnabled)
                .height(KcodeSize.touchTarget),
            interactionSource = saveInteraction,
            shape = RoundedCornerShape(KcodeRadius.card),
            colors = ButtonDefaults.buttonColors(
                containerColor = Leaf,
                disabledContainerColor = Mist,
                disabledContentColor = SoftInk,
            ),
        ) {
            Text(text(UiText.SaveSettings), style = MaterialTheme.typography.labelLarge)
        }
    }
}
