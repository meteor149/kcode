@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.modelsettings.ui

import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.model.ModelConnectionChoice
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.ui.api.PersistenceFailure
import ai.meteor.kcode.ui.component.ApiKeyField
import ai.meteor.kcode.ui.component.CompactSectionLabel
import ai.meteor.kcode.ui.component.CompactSettingsGroup
import ai.meteor.kcode.ui.component.ConnectionTextField
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.ui.design.Error
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.Mist
import ai.meteor.kcode.ui.design.SoftInk
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun ModelServiceSettings(
    catalog: ModelCatalogSnapshot,
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
    val specification = catalog.provider(provider) ?: return
    val saveInteraction = remember { MutableInteractionSource() }
    val saveEnabled = (!specification.requirements.apiKey || apiKey.isNotBlank()) &&
        (!specification.requirements.endpoint || endpoint.isNotBlank()) &&
        (!specification.requirements.region || region.isNotBlank()) &&
        (!specification.requirements.deployment || deployment.isNotBlank())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSectionLabel(text(UiText.Provider))
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            catalog.providers.forEachIndexed { index, itemSpec ->
                val item = itemSpec.provider
                CompactProviderRow(item, provider == item) { onProviderChange(item) }
                if (index != catalog.providers.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
                }
            }
        }
        if (specification.requirements.apiKey) {
            CompactSectionLabel(text(UiText.ApiKey), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ApiKeyField(
                    apiKey,
                    showKey,
                    onApiKeyChange,
                    onToggleKey,
                    placeholder = text(UiText.EnterApiKey),
                    showLabel = text(UiText.Show),
                    hideLabel = text(UiText.Hide),
                )
            }
        }
        if (specification.requirements.endpoint) {
            CompactSectionLabel(text(UiText.Endpoint), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(
                    value = endpoint,
                    placeholder = specification.defaults.endpoint,
                    onValueChange = onEndpointChange,
                )
            }
        }
        if (specification.requirements.deployment) {
            CompactSectionLabel(text(UiText.Deployment), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(deployment, text(UiText.DeploymentHint), onDeploymentChange)
            }
            CompactSectionLabel(text(UiText.ApiVersion), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(apiVersion, specification.defaults.apiVersion, onApiVersionChange)
            }
        }
        if (specification.requirements.region) {
            CompactSectionLabel(text(UiText.Region), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                if (specification.regionChoices.isEmpty()) {
                    ConnectionTextField(region, specification.defaults.region, onRegionChange)
                } else {
                    specification.regionChoices.forEachIndexed { index, choice ->
                        RegionChoiceRow(choice, region == choice.value) { onRegionChange(choice.value) }
                        if (index != specification.regionChoices.lastIndex) HorizontalDivider(thickness = .5.dp, color = Hairline)
                    }
                }
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


@Composable
private fun RegionChoiceRow(choice: ModelConnectionChoice, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = KcodeSize.touchTarget)
            .pressClickable(onClick = onClick)
            .clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            choice.displayNames[LocalAppLanguage.current.code] ?: choice.displayNames["en"] ?: choice.value,
            Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.bodyMedium,
        )
        if (selected) KcodeIcon(KcodeIconAsset.Check, LeafInk, Modifier.size(20.dp))
    }
}
