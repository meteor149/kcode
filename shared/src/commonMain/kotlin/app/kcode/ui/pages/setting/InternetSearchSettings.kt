@file:OptIn(ExperimentalMaterial3Api::class)

package app.kcode.ui.pages.setting

import app.kcode.ui.design.Mist
import app.kcode.ui.design.Leaf
import app.kcode.ui.design.LeafInk
import app.kcode.ui.design.Ink
import app.kcode.ui.design.SoftInk
import app.kcode.ui.design.Hairline

import app.kcode.ui.design.KcodeRadius
import app.kcode.ui.design.KcodeSize
import app.kcode.ui.design.KcodeSpacing

import app.kcode.model.requiresApiKey
import app.kcode.tools.search.WebSearchProvider
import app.kcode.ui.component.PressScaleStyle
import app.kcode.ui.component.pressClickable
import app.kcode.ui.component.pressScale
import app.kcode.localization.UiText
import app.kcode.localization.text
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
import app.kcode.ui.component.ApiKeyField
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
