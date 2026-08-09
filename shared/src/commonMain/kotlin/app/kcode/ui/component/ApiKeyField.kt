package app.kcode.ui.component

import app.kcode.localization.UiText
import app.kcode.localization.text
import app.kcode.ui.design.KcodeRadius
import app.kcode.ui.design.KcodeSize
import app.kcode.ui.design.KcodeSpacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ApiKeyField(
    value: String,
    showKey: Boolean,
    onValueChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(KcodeRadius.control),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE4E5E2)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(KcodeSize.touchTarget).padding(horizontal = KcodeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
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
