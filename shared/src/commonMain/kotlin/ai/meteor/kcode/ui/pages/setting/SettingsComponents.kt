@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.setting

import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.PaleMint
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.localization.providerName
import ai.meteor.kcode.localization.providerNote
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
internal enum class SecondarySettingsIcon {
    Chinese, English,
    OpenAI, DeepSeek, Glm,
    Google, Exa, BrightData,
    App, Adb, Root,
}

@Composable
internal fun SecondarySettingsIcon(icon: SecondarySettingsIcon, selected: Boolean) {
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(if (selected) PaleMint else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (selected) LeafInk else SoftInk.copy(alpha = .78f)
        when (icon) {
            SecondarySettingsIcon.Chinese -> Text("中", color = color, style = MaterialTheme.typography.labelLarge)
            SecondarySettingsIcon.English -> Text("A", color = color, style = MaterialTheme.typography.labelLarge)
            else -> Canvas(Modifier.size(19.dp)) {
                val strokeWidth = 1.65.dp.toPx()
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                when (icon) {
                    SecondarySettingsIcon.OpenAI -> {
                        drawCircle(color, size.minDimension * .34f, style = stroke)
                        repeat(3) { index ->
                            val angle = index * (kotlin.math.PI / 3.0)
                            val dx = kotlin.math.cos(angle).toFloat() * size.width * .38f
                            val dy = kotlin.math.sin(angle).toFloat() * size.height * .38f
                            drawLine(color, center - Offset(dx, dy), center + Offset(dx, dy), strokeWidth, StrokeCap.Round)
                        }
                    }
                    SecondarySettingsIcon.DeepSeek -> {
                        val wave = Path().apply {
                            moveTo(size.width * .08f, size.height * .58f)
                            cubicTo(size.width * .28f, size.height * .26f, size.width * .52f, size.height * .8f, size.width * .92f, size.height * .38f)
                        }
                        drawPath(wave, color, style = stroke)
                        drawCircle(color, size.minDimension * .09f, Offset(size.width * .8f, size.height * .33f))
                    }
                    SecondarySettingsIcon.Glm -> {
                        val side = size.minDimension * .26f
                        listOf(.18f to .18f, .56f to .18f, .18f to .56f, .56f to .56f).forEach { (x, y) ->
                            drawRoundRect(
                                color,
                                Offset(size.width * x, size.height * y),
                                Size(side, side),
                                CornerRadius(side * .22f),
                                style = stroke,
                            )
                        }
                    }
                    SecondarySettingsIcon.Google -> {
                        drawCircle(color, size.minDimension * .39f, style = stroke)
                        drawLine(color, Offset(size.width * .52f, size.height * .5f), Offset(size.width * .9f, size.height * .5f), strokeWidth, StrokeCap.Round)
                    }
                    SecondarySettingsIcon.Exa -> {
                        drawLine(color, Offset(size.width * .2f, size.height * .2f), Offset(size.width * .8f, size.height * .8f), strokeWidth, StrokeCap.Round)
                        drawLine(color, Offset(size.width * .8f, size.height * .2f), Offset(size.width * .2f, size.height * .8f), strokeWidth, StrokeCap.Round)
                    }
                    SecondarySettingsIcon.BrightData -> {
                        listOf(.3f to .3f, .7f to .3f, .3f to .7f, .7f to .7f).forEach { (x, y) ->
                            drawCircle(color, size.minDimension * .11f, Offset(size.width * x, size.height * y))
                        }
                    }
                    SecondarySettingsIcon.App -> {
                        drawRoundRect(color, Offset(size.width * .25f, size.height * .08f), Size(size.width * .5f, size.height * .84f), CornerRadius(size.width * .1f), style = stroke)
                        drawCircle(color, size.minDimension * .035f, Offset(size.width * .5f, size.height * .79f))
                    }
                    SecondarySettingsIcon.Adb -> {
                        drawRoundRect(color, Offset(size.width * .08f, size.height * .2f), Size(size.width * .84f, size.height * .62f), CornerRadius(size.width * .08f), style = stroke)
                        drawLine(color, Offset(size.width * .24f, size.height * .38f), Offset(size.width * .39f, size.height * .5f), strokeWidth, StrokeCap.Round)
                        drawLine(color, Offset(size.width * .39f, size.height * .5f), Offset(size.width * .24f, size.height * .62f), strokeWidth, StrokeCap.Round)
                        drawLine(color, Offset(size.width * .53f, size.height * .63f), Offset(size.width * .72f, size.height * .63f), strokeWidth, StrokeCap.Round)
                    }
                    SecondarySettingsIcon.Root -> {
                        val shield = Path().apply {
                            moveTo(size.width * .5f, size.height * .08f)
                            lineTo(size.width * .82f, size.height * .23f)
                            lineTo(size.width * .76f, size.height * .66f)
                            quadraticTo(size.width * .66f, size.height * .84f, size.width * .5f, size.height * .92f)
                            quadraticTo(size.width * .34f, size.height * .84f, size.width * .24f, size.height * .66f)
                            lineTo(size.width * .18f, size.height * .23f)
                            close()
                        }
                        drawPath(shield, color, style = stroke)
                        drawLine(color, Offset(size.width * .5f, size.height * .34f), Offset(size.width * .5f, size.height * .65f), strokeWidth, StrokeCap.Round)
                        drawCircle(color, size.minDimension * .045f, Offset(size.width * .5f, size.height * .75f))
                    }
                    SecondarySettingsIcon.Chinese,
                    SecondarySettingsIcon.English -> Unit
                }
            }
        }
    }
}

@Composable
internal fun ConnectionTextField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(KcodeRadius.control),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Hairline),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(KcodeSize.touchTarget)
                .padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
            cursorBrush = SolidColor(Leaf),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = SoftInk.copy(.65f), style = MaterialTheme.typography.bodyMedium)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
internal fun CompactSectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier.padding(start = KcodeSpacing.sm, bottom = KcodeSpacing.xs),
        color = SoftInk,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
internal fun CompactSettingsGroup(
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
        SecondarySettingsIcon(
            when (provider) {
                ModelProvider.OpenAI -> SecondarySettingsIcon.OpenAI
                ModelProvider.AzureOpenAI -> SecondarySettingsIcon.OpenAI
                ModelProvider.Anthropic -> SecondarySettingsIcon.Glm
                ModelProvider.Google -> SecondarySettingsIcon.Google
                ModelProvider.DeepSeek -> SecondarySettingsIcon.DeepSeek
                ModelProvider.OpenRouter -> SecondarySettingsIcon.Google
                ModelProvider.Bedrock -> SecondarySettingsIcon.Glm
                ModelProvider.Mistral -> SecondarySettingsIcon.DeepSeek
                ModelProvider.Alibaba -> SecondarySettingsIcon.Glm
                ModelProvider.Ollama -> SecondarySettingsIcon.App
                ModelProvider.GLM -> SecondarySettingsIcon.Glm
            },
            selected,
        )
        Column(Modifier.padding(start = KcodeSpacing.md).weight(1f)) {
            Text(providerName(provider), color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text(providerNote(provider), Modifier.padding(top = KcodeSpacing.hair), color = SoftInk, style = MaterialTheme.typography.bodySmall)
        }
        Text(if (selected) "✓" else "›", color = if (selected) LeafInk else SoftInk.copy(alpha = .45f), fontSize = 20.sp)
    }
}
