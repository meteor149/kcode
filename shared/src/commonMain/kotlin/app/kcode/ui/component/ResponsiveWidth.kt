package app.kcode.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
internal fun BoxWithResponsiveWidth(content: @Composable (Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) { content(maxWidth) }
}
