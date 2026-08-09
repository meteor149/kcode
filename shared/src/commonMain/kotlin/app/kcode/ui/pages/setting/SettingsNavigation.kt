@file:OptIn(ExperimentalMaterial3Api::class)

package app.kcode.ui.pages.setting

import app.kcode.ui.design.Ink
import app.kcode.ui.design.SoftInk
import app.kcode.ui.design.Hairline

import app.kcode.ui.design.KcodeRadius
import app.kcode.ui.design.KcodeSize
import app.kcode.ui.design.KcodeSpacing

import app.kcode.model.ModelConfiguration
import app.kcode.settings.ShellExecutionMode
import app.kcode.tools.search.WebSearchProvider
import app.kcode.ui.component.pressClickable
import app.kcode.localization.AppLanguage
import app.kcode.localization.UiText
import app.kcode.localization.providerName
import app.kcode.localization.text
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kcode.ui.component.FloatingCircleButton
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
            Canvas(Modifier.size(19.dp)) {
                val stroke = size.minDimension * .085f
                if (isRoot) {
                    drawLine(
                        Ink,
                        Offset(size.width * .2f, size.height * .2f),
                        Offset(size.width * .8f, size.height * .8f),
                        stroke,
                        StrokeCap.Round
                    )
                    drawLine(
                        Ink,
                        Offset(size.width * .8f, size.height * .2f),
                        Offset(size.width * .2f, size.height * .8f),
                        stroke,
                        StrokeCap.Round
                    )
                } else {
                    drawLine(
                        Ink,
                        Offset(size.width * .68f, size.height * .15f),
                        Offset(size.width * .3f, size.height * .5f),
                        stroke,
                        StrokeCap.Round
                    )
                    drawLine(
                        Ink,
                        Offset(size.width * .3f, size.height * .5f),
                        Offset(size.width * .68f, size.height * .85f),
                        stroke,
                        StrokeCap.Round
                    )
                }
            }
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
    language: AppLanguage,
    current: ModelConfiguration?,
    onLanguage: () -> Unit,
    onModelService: () -> Unit,
    webSearchProvider: WebSearchProvider,
    onInternetSearch: () -> Unit,
    shellSettingsAvailable: Boolean,
    shellExecutionMode: ShellExecutionMode,
    onShellExecution: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSectionLabel(text(UiText.General))
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            SettingsNavigationRow(
                icon = SettingsGlyph.Language,
                title = text(UiText.Language),
                description = if (language == AppLanguage.Chinese) text(UiText.SimplifiedChinese) else text(UiText.English),
                onClick = onLanguage,
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            SettingsNavigationRow(
                icon = SettingsGlyph.Model,
                title = text(UiText.ModelService),
                description = current?.let { providerName(it.provider) } ?: text(UiText.ModelProviderDescription),
                onClick = onModelService,
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            SettingsNavigationRow(
                icon = SettingsGlyph.Search,
                title = text(UiText.InternetSearch),
                description = webSearchProvider.displayName(),
                onClick = onInternetSearch,
            )
            if (shellSettingsAvailable) {
                HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
                SettingsNavigationRow(
                    icon = SettingsGlyph.Shell,
                    title = text(UiText.ShellExecution),
                    description = shellModeTitle(shellExecutionMode),
                    onClick = onShellExecution,
                )
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: SettingsGlyph,
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
        SettingsGlyphIcon(icon)
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
        Canvas(Modifier.size(width = 10.dp, height = 18.dp)) {
            val stroke = 1.8.dp.toPx()
            drawLine(
                SoftInk.copy(alpha = .42f),
                Offset(size.width * .25f, size.height * .14f),
                Offset(size.width * .76f, size.height * .5f),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                SoftInk.copy(alpha = .42f),
                Offset(size.width * .76f, size.height * .5f),
                Offset(size.width * .25f, size.height * .86f),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}

private enum class SettingsGlyph { Language, Model, Search, Shell }

@Composable
private fun SettingsGlyphIcon(icon: SettingsGlyph) {
    Canvas(Modifier.size(26.dp)) {
        val color = SoftInk.copy(alpha = .82f)
        val strokeWidth = 1.8.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        when (icon) {
            SettingsGlyph.Language -> {
                drawCircle(color, radius = size.minDimension * .41f, style = stroke)
                drawOval(
                    color,
                    topLeft = Offset(size.width * .32f, size.height * .09f),
                    size = Size(size.width * .36f, size.height * .82f),
                    style = stroke,
                )
                drawLine(color, Offset(size.width * .12f, size.height * .5f), Offset(size.width * .88f, size.height * .5f), strokeWidth, StrokeCap.Round)
            }
            SettingsGlyph.Model -> {
                val radius = size.minDimension * .105f
                val centers = listOf(
                    Offset(size.width * .25f, size.height * .28f),
                    Offset(size.width * .74f, size.height * .25f),
                    Offset(size.width * .5f, size.height * .75f),
                )
                drawLine(color, centers[0], centers[1], strokeWidth, StrokeCap.Round)
                drawLine(color, centers[1], centers[2], strokeWidth, StrokeCap.Round)
                drawLine(color, centers[2], centers[0], strokeWidth, StrokeCap.Round)
                centers.forEach { drawCircle(color, radius, it, style = stroke) }
            }
            SettingsGlyph.Search -> {
                drawCircle(color, radius = size.minDimension * .3f, center = Offset(size.width * .43f, size.height * .42f), style = stroke)
                drawLine(
                    color,
                    Offset(size.width * .65f, size.height * .65f),
                    Offset(size.width * .88f, size.height * .88f),
                    strokeWidth,
                    StrokeCap.Round,
                )
            }
            SettingsGlyph.Shell -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(size.width * .08f, size.height * .17f),
                    size = Size(size.width * .84f, size.height * .66f),
                    cornerRadius = CornerRadius(size.width * .1f),
                    style = stroke,
                )
                drawLine(color, Offset(size.width * .25f, size.height * .38f), Offset(size.width * .4f, size.height * .5f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .4f, size.height * .5f), Offset(size.width * .25f, size.height * .62f), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(size.width * .53f, size.height * .63f), Offset(size.width * .72f, size.height * .63f), strokeWidth, StrokeCap.Round)
            }
        }
    }
}
