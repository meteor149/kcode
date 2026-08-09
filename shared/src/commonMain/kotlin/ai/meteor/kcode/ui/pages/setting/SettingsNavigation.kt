@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.setting

import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.tools.search.WebSearchProvider
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.providerName
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.meteor.kcode.ui.component.FloatingCircleButton
@Composable
internal fun SettingsWindowHeader(
    title: String,
    isRoot: Boolean,
    onNavigation: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(88.dp)) {
        FloatingCircleButton(
            description = if (isRoot) text(UiText.BackToChat) else text(UiText.Settings),
            onClick = onNavigation,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp),
            size = KcodeSize.touchTarget,
        ) {
            KcodeIcon(
                if (isRoot) KcodeIconAsset.Close else KcodeIconAsset.Back,
                Ink,
                Modifier.size(19.dp),
            )
        }
        Text(
            title,
            Modifier.align(Alignment.Center),
            color = Ink,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
internal fun SettingsHome(
    language: AppLanguage,
    current: ModelConfiguration?,
    onLanguage: () -> Unit,
    onModelService: () -> Unit,
    webSearchProvider: WebSearchProvider,
    onInternetSearch: () -> Unit,
    shellSettingsAvailable: Boolean,
    shellExecutionMode: ShellExecutionMode,
    onShellExecution: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSectionLabel(text(UiText.General))
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            SettingsNavigationRow(
                icon = SettingsGlyph.Language,
                title = text(UiText.Language),
                description = if (language == AppLanguage.Chinese) text(UiText.SimplifiedChinese) else text(UiText.English),
                onClick = onLanguage,
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            SettingsNavigationRow(
                icon = SettingsGlyph.Model,
                title = text(UiText.ModelService),
                description = current?.let { providerName(it.provider) } ?: text(UiText.ModelProviderDescription),
                onClick = onModelService,
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            SettingsNavigationRow(
                icon = SettingsGlyph.Search,
                title = text(UiText.InternetSearch),
                description = webSearchProvider.displayName(),
                onClick = onInternetSearch,
            )
            if (shellSettingsAvailable) {
                HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
                SettingsNavigationRow(
                    icon = SettingsGlyph.Shell,
                    title = text(UiText.ShellExecution),
                    description = shellModeTitle(shellExecutionMode),
                    onClick = onShellExecution,
                )
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: SettingsGlyph,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .pressClickable(onClick = onClick).clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = KcodeSpacing.hair, vertical = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyphIcon(icon)
        Column(Modifier.padding(start = KcodeSpacing.md).weight(1f)) {
            Text(title, color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                Modifier.padding(top = 2.dp),
                color = SoftInk,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        KcodeIcon(KcodeIconAsset.ChevronRight, SoftInk.copy(alpha = .42f), Modifier.size(width = 10.dp, height = 18.dp))
    }
}

private enum class SettingsGlyph { Language, Model, Search, Shell }

@Composable
private fun SettingsGlyphIcon(icon: SettingsGlyph) {
    val asset = when (icon) {
        SettingsGlyph.Language -> KcodeIconAsset.Language
        SettingsGlyph.Model -> KcodeIconAsset.Model
        SettingsGlyph.Search -> KcodeIconAsset.Search
        SettingsGlyph.Shell -> KcodeIconAsset.Terminal
    }
    KcodeIcon(asset, SoftInk.copy(alpha = .82f), Modifier.size(26.dp))
}
