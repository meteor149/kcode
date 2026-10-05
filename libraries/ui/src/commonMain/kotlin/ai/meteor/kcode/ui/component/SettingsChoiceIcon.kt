package ai.meteor.kcode.ui.component

import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.PaleMint
import ai.meteor.kcode.ui.design.SoftInk
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun SettingsChoiceIcon(icon: KcodeIconAsset, selected: Boolean) {
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(if (selected) PaleMint else MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (selected) LeafInk else SoftInk.copy(alpha = .78f)
        KcodeIcon(
            asset = icon,
            tint = color,
            modifier = Modifier.size(19.dp),
        )
    }
}
