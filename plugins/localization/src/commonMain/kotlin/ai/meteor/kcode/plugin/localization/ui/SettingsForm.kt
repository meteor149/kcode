@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.localization.ui

import ai.meteor.kcode.localization.AppLanguage
import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.localization.LocalTranslationCatalog
import ai.meteor.kcode.ui.component.CompactSettingsGroup
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.SettingsChoiceIcon
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.LeafInk
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp

@Composable
fun LanguageSettings(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            val options = LocalTranslationCatalog.current?.snapshot()?.languages.orEmpty()
            options.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
                SettingsChoiceRow(
                    icon = KcodeIconAsset.Language,
                    label = option.displayNames[LocalAppLanguage.current.code] ?: option.displayNames["en"] ?: option.language.code,
                    selected = language == option.language,
                    onClick = { onLanguageChange(option.language) },
                )
            }
        }
    }
}

@Composable
private fun SettingsChoiceRow(icon: KcodeIconAsset, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .pressClickable(onClick = onClick).clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = KcodeSpacing.hair, vertical = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsChoiceIcon(icon, selected)
        Text(label, Modifier.padding(start = KcodeSpacing.md).weight(1f), color = Ink, style = MaterialTheme.typography.bodyLarge)
        if (selected) {
            KcodeIcon(KcodeIconAsset.Check, LeafInk, Modifier.size(20.dp))
        }
    }
}