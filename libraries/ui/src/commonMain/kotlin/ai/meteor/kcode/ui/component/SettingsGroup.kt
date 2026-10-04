package ai.meteor.kcode.ui.component

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.SoftInk
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun CompactSectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier.padding(start = KcodeSpacing.sm, bottom = KcodeSpacing.xs),
        color = SoftInk,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun CompactSettingsGroup(
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
