@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.setting

import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Error

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
@Composable
internal fun ShellExecutionSettings(
    selected: ShellExecutionMode,
    onSelected: (ShellExecutionMode) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            ShellModeRow(
                mode = ShellExecutionMode.App,
                description = text(UiText.ShellModeAppDescription),
                selected = selected == ShellExecutionMode.App,
                onClick = { onSelected(ShellExecutionMode.App) },
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            ShellModeRow(
                mode = ShellExecutionMode.Adb,
                description = text(UiText.ShellModeAdbDescription),
                selected = selected == ShellExecutionMode.Adb,
                onClick = { onSelected(ShellExecutionMode.Adb) },
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            ShellModeRow(
                mode = ShellExecutionMode.Root,
                description = text(UiText.ShellModeRootDescription),
                selected = selected == ShellExecutionMode.Root,
                onClick = { onSelected(ShellExecutionMode.Root) },
            )
        }
        SettingsWarningNotice(
            text(UiText.ShellExecutionWarning),
            Modifier.padding(top = KcodeSpacing.sm),
        )
    }
}

@Composable
private fun ShellModeRow(
    mode: ShellExecutionMode,
    description: String,
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
            when (mode) {
                ShellExecutionMode.App -> SecondarySettingsIcon.App
                ShellExecutionMode.Adb -> SecondarySettingsIcon.Adb
                ShellExecutionMode.Root -> SecondarySettingsIcon.Root
            },
            selected,
        )
        Column(Modifier.padding(start = KcodeSpacing.md).weight(1f)) {
            Text(shellModeTitle(mode), color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text(description, Modifier.padding(top = KcodeSpacing.hair), color = SoftInk, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) {
            KcodeIcon(KcodeIconAsset.Check, LeafInk, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SettingsWarningNotice(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KcodeRadius.card),
        color = Error.copy(alpha = .07f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KcodeIcon(KcodeIconAsset.Info, Error.copy(alpha = .85f), Modifier.size(18.dp))
            Text(
                message,
                Modifier.padding(start = KcodeSpacing.sm).weight(1f),
                color = Error.copy(alpha = .9f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun shellModeTitle(mode: ShellExecutionMode): String = text(
    when (mode) {
        ShellExecutionMode.App -> UiText.ShellModeApp
        ShellExecutionMode.Adb -> UiText.ShellModeAdb
        ShellExecutionMode.Root -> UiText.ShellModeRoot
    },
)

@Composable
internal fun LanguageSettings(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            SettingsChoiceRow(
                icon = SecondarySettingsIcon.Chinese,
                label = text(UiText.SimplifiedChinese),
                selected = language == AppLanguage.Chinese,
                onClick = { onLanguageChange(AppLanguage.Chinese) },
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            SettingsChoiceRow(
                icon = SecondarySettingsIcon.English,
                label = text(UiText.English),
                selected = language == AppLanguage.English,
                onClick = { onLanguageChange(AppLanguage.English) },
            )
        }
    }
}

@Composable
private fun SettingsChoiceRow(icon: SecondarySettingsIcon, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .pressClickable(onClick = onClick).clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = KcodeSpacing.hair, vertical = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondarySettingsIcon(icon, selected)
        Text(label, Modifier.padding(start = KcodeSpacing.md).weight(1f), color = Ink, style = MaterialTheme.typography.bodyLarge)
        if (selected) {
            KcodeIcon(KcodeIconAsset.Check, LeafInk, Modifier.size(20.dp))
        }
    }
}
