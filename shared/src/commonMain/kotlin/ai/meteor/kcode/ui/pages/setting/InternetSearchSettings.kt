@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.setting

import ai.meteor.kcode.ui.design.Mist
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.model.requiresApiKey
import ai.meteor.kcode.tools.search.WebSearchProvider
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.meteor.kcode.ui.component.ApiKeyField
@Composable
internal fun InternetSearchSettings(
    provider: WebSearchProvider,
    brightDataApiKey: String,
    exaApiKey: String,
    showKey: Boolean,
    onProviderChange: (WebSearchProvider) -> Unit,
    onBrightDataApiKeyChange: (String) -> Unit,
    onExaApiKeyChange: (String) -> Unit,
    onToggleKey: () -> Unit,
    onSave: () -> Unit,
) {
    val saveInteraction = remember { MutableInteractionSource() }
    val saveEnabled = !provider.requiresApiKey ||
        (provider == WebSearchProvider.Exa && exaApiKey.isNotBlank()) ||
        (provider == WebSearchProvider.BrightData && brightDataApiKey.isNotBlank())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSectionLabel(text(UiText.SearchProvider))
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            WebSearchProvider.entries.forEachIndexed { index, item ->
                WebSearchProviderRow(item, provider == item) { onProviderChange(item) }
                if (index != WebSearchProvider.entries.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
                }
            }
        }
        if (provider.requiresApiKey) {
            val selectedKey = if (provider == WebSearchProvider.Exa) exaApiKey else brightDataApiKey
            CompactSectionLabel(text(UiText.ApiKey), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ApiKeyField(
                    selectedKey,
                    showKey,
                    if (provider == WebSearchProvider.Exa) onExaApiKeyChange else onBrightDataApiKeyChange,
                    onToggleKey,
                )
            }
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
private fun WebSearchProviderRow(provider: WebSearchProvider, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .pressClickable(onClick = onClick).clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = KcodeSpacing.hair, vertical = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondarySettingsIcon(
            when (provider) {
                WebSearchProvider.Google -> SecondarySettingsIcon.Google
                WebSearchProvider.Exa -> SecondarySettingsIcon.Exa
                WebSearchProvider.BrightData -> SecondarySettingsIcon.BrightData
            },
            selected,
        )
        Column(Modifier.padding(start = KcodeSpacing.md).weight(1f)) {
            Text(provider.displayName(), color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text(
                text(if (provider.requiresApiKey) UiText.ApiKeyRequired else UiText.NoApiKeyRequired),
                color = SoftInk,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(if (selected) "✓" else "", color = LeafInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun WebSearchProvider.displayName(): String = when (this) {
    WebSearchProvider.Google -> "Google"
    WebSearchProvider.Exa -> "Exa"
    WebSearchProvider.BrightData -> "Bright Data"
}
