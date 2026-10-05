@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.modelsettings.ui

import ai.meteor.kcode.plugin.ui.api.LocalModelCatalog
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.ui.api.providerName
import ai.meteor.kcode.plugin.ui.api.providerNote
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.SettingsChoiceIcon
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.SoftInk
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

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
        SettingsChoiceIcon(
            KcodeIconAsset.entries.firstOrNull {
                it.name == LocalModelCatalog.current.provider(provider)?.iconId
            } ?: KcodeIconAsset.Model,
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