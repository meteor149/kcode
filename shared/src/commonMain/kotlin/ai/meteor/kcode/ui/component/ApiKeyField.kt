package ai.meteor.kcode.ui.component

import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun ApiKeyField(
    value: String,
    showKey: Boolean,
    onValueChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fieldState = rememberTextFieldState(initialText = value)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(value) {
        if (fieldState.text.toString() != value) {
            fieldState.setTextAndPlaceCursorAtEnd(value)
        }
    }
    LaunchedEffect(fieldState) {
        snapshotFlow { fieldState.text.toString() }
            .distinctUntilChanged()
            .collect { updatedValue ->
                if (updatedValue != currentValue) currentOnValueChange(updatedValue)
            }
    }

    Surface(
        shape = RoundedCornerShape(KcodeRadius.control),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE4E5E2)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(KcodeSize.touchTarget).padding(horizontal = KcodeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicSecureTextField(
                state = fieldState,
                modifier = Modifier.weight(1f),
                textObfuscationMode = if (showKey) {
                    TextObfuscationMode.Visible
                } else {
                    TextObfuscationMode.Hidden
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                decorator = { inner ->
                    Box {
                        if (fieldState.text.isEmpty()) {
                            Text(
                                text(UiText.EnterApiKey),
                                color = colors.onSurfaceVariant.copy(alpha = .65f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        inner()
                    }
                },
            )
            Text(
                if (showKey) text(UiText.Hide) else text(UiText.Show),
                Modifier.pressClickable(style = PressScaleStyle.Button, onClick = onToggleVisibility)
                    .clip(RoundedCornerShape(KcodeSpacing.xs)).padding(KcodeSpacing.xs),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
