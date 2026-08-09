@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.setting

import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.PaleMint
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.localization.providerName
import ai.meteor.kcode.localization.providerNote
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
internal enum class SecondarySettingsIcon {
    Chinese, English,
    OpenAI, DeepSeek, Glm,
    Google, Exa, BrightData,
    App, Adb, Root,
}

@Composable
internal fun SecondarySettingsIcon(icon: SecondarySettingsIcon, selected: Boolean) {
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(if (selected) PaleMint else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (selected) LeafInk else SoftInk.copy(alpha = .78f)
        when (icon) {
            SecondarySettingsIcon.Chinese -> Text("中", color = color, style = MaterialTheme.typography.labelLarge)
            SecondarySettingsIcon.English -> Text("A", color = color, style = MaterialTheme.typography.labelLarge)
            else -> KcodeIcon(
                asset = when (icon) {
                    SecondarySettingsIcon.OpenAI -> KcodeIconAsset.OpenAI
                    SecondarySettingsIcon.DeepSeek -> KcodeIconAsset.DeepSeek
                    SecondarySettingsIcon.Glm -> KcodeIconAsset.Glm
                    SecondarySettingsIcon.Google -> KcodeIconAsset.Google
                    SecondarySettingsIcon.Exa -> KcodeIconAsset.Exa
                    SecondarySettingsIcon.BrightData -> KcodeIconAsset.BrightData
                    SecondarySettingsIcon.App -> KcodeIconAsset.Device
                    SecondarySettingsIcon.Adb -> KcodeIconAsset.Terminal
                    SecondarySettingsIcon.Root -> KcodeIconAsset.Root
                    SecondarySettingsIcon.Chinese,
                    SecondarySettingsIcon.English -> error("Text language badges are rendered above")
                },
                tint = color,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
internal fun ConnectionTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(KcodeRadius.control),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Hairline),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(KcodeSize.touchTarget)
                .padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
            cursorBrush = SolidColor(Leaf),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = SoftInk.copy(.65f), style = MaterialTheme.typography.bodyMedium)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
internal fun CompactSectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier.padding(start = KcodeSpacing.sm, bottom = KcodeSpacing.xs),
        color = SoftInk,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
internal fun CompactSettingsGroup(
    contentPadding: PaddingValues = PaddingValues(KcodeSpacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KcodeRadius.panel),
        color = Panel,
    ) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
    }
}

@Composable
internal fun CompactProviderRow(
    provider: ModelProvider,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .pressClickable(onClick = onClick).clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = KcodeSpacing.hair, vertical = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondarySettingsIcon(
            when (provider) {
                ModelProvider.OpenAI -> SecondarySettingsIcon.OpenAI
                ModelProvider.AzureOpenAI -> SecondarySettingsIcon.OpenAI
                ModelProvider.Anthropic -> SecondarySettingsIcon.Glm
                ModelProvider.Google -> SecondarySettingsIcon.Google
                ModelProvider.DeepSeek -> SecondarySettingsIcon.DeepSeek
                ModelProvider.OpenRouter -> SecondarySettingsIcon.Google
                ModelProvider.Bedrock -> SecondarySettingsIcon.Glm
                ModelProvider.Mistral -> SecondarySettingsIcon.DeepSeek
                ModelProvider.Alibaba -> SecondarySettingsIcon.Glm
                ModelProvider.Ollama -> SecondarySettingsIcon.App
                ModelProvider.GLM -> SecondarySettingsIcon.Glm
            },
            selected,
        )
        Column(Modifier.padding(start = KcodeSpacing.md).weight(1f)) {
            Text(providerName(provider), color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text(providerNote(provider), Modifier.padding(top = KcodeSpacing.hair), color = SoftInk, style = MaterialTheme.typography.bodySmall)
        }
        KcodeIcon(
            asset = if (selected) KcodeIconAsset.Check else KcodeIconAsset.ChevronRight,
            tint = if (selected) LeafInk else SoftInk.copy(alpha = .45f),
            modifier = Modifier.size(20.dp),
        )
    }
}
