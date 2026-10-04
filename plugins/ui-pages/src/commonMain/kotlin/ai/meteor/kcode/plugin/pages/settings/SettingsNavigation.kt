@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.pages.settings

import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.CompactSectionLabel
import ai.meteor.kcode.ui.component.CompactSettingsGroup
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.plugin.ui.api.SettingsPageRequest
import ai.meteor.kcode.plugin.ui.api.SettingsSection
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
    request: SettingsPageRequest,
    sections: List<SettingsSection>,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSectionLabel(text(UiText.General))
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            sections.forEachIndexed { index, section ->
                if (index > 0) {
                    HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
                }
                SettingsNavigationRow(
                    icon = section.icon,
                    title = section.title(),
                    description = section.description(request),
                    onClick = { onSelect(section.id) },
                )
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: KcodeIconAsset,
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
        KcodeIcon(icon, SoftInk.copy(alpha = .82f), Modifier.size(26.dp))
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
