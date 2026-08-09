package ai.meteor.kcode.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun FloatingCircleButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    width: Dp = size,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    shadowElevation: Dp = 7.dp,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(interaction, PressScaleStyle.Button).size(width = width, height = size)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        interactionSource = interaction,
        shape = CircleShape,
        color = containerColor,
        border = border,
        shadowElevation = shadowElevation,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
