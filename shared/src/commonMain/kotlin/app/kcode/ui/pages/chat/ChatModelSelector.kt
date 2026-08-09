@file:OptIn(ExperimentalMaterial3Api::class)

package app.kcode.ui.pages.chat

import app.kcode.ui.design.Mist
import app.kcode.ui.design.Leaf
import app.kcode.ui.design.Ink
import app.kcode.ui.design.SoftInk
import app.kcode.ui.design.Hairline

import app.kcode.ui.design.KcodeRadius
import app.kcode.ui.design.KcodeSpacing
import app.kcode.model.ModelConfiguration
import app.kcode.model.modelOption
import app.kcode.model.modelsFor
import app.kcode.ui.component.BubblePlacement
import app.kcode.ui.component.PressScaleStyle
import app.kcode.ui.component.pressClickable
import app.kcode.ui.component.pressScale
import app.kcode.localization.UiText
import app.kcode.localization.modelDescription
import app.kcode.localization.modelName
import app.kcode.localization.providerName
import app.kcode.localization.text
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
private enum class ModelPopupPage { Configuration, Models }

@Composable
internal fun ModelConfigurationBubble(
    expanded: Boolean,
    configuration: ModelConfiguration,
    placement: BubblePlacement,
    onDismissRequest: () -> Unit,
    onChange: (ModelConfiguration) -> Unit,
) {
    var selectedModelId by remember(configuration) { mutableStateOf(configuration.modelId) }
    var temperature by remember(configuration) { mutableStateOf(configuration.temperature.toFloat()) }
    var page by remember { mutableStateOf(ModelPopupPage.Configuration) }
    val models = modelsFor(configuration.provider)
    val selectedModel = models.firstOrNull { it.id == selectedModelId } ?: models.first()
    val creativityLevels = listOf(
        1.0f to text(UiText.CreativityUltraHigh),
        .8f to text(UiText.CreativityHighest),
        .6f to text(UiText.CreativityVeryHigh),
        .4f to text(UiText.CreativityHigh),
        .2f to text(UiText.CreativityMedium),
        0f to text(UiText.CreativityLight),
    )
    val selectedCreativity = creativityLevels.minBy { abs(it.first - temperature) }.first

    KcodeBubblePopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        placement = placement,
        minWidth = 240.dp,
        maxWidth = 280.dp,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = KcodeSpacing.sm)) {
            if (page == ModelPopupPage.Configuration) {
                PopupNavigationRow(
                    label = text(UiText.ModelLabel),
                    value = modelName(selectedModel),
                    onClick = { page = ModelPopupPage.Models },
                )
                HorizontalDivider(Modifier.padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair), color = Hairline, thickness = .7.dp)
                PopupSectionLabel(text(UiText.Creativity))
                creativityLevels.forEach { (level, label) ->
                    PopupChoiceRow(
                        title = label,
                        selected = level == selectedCreativity,
                        onClick = {
                            temperature = level
                            onChange(configuration.copy(modelId = selectedModelId, temperature = level.toDouble()))
                        },
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().pressClickable { page = ModelPopupPage.Configuration }
                        .clip(RoundedCornerShape(KcodeRadius.control))
                        .padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("‹", Modifier.width(KcodeSpacing.xl), color = Ink, fontSize = 28.sp, lineHeight = 28.sp)
                    Column {
                        Text(text(UiText.ChooseModel), color = Ink, style = MaterialTheme.typography.titleMedium)
                        Text(providerName(configuration.provider), Modifier.padding(top = KcodeSpacing.hair), color = SoftInk, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair), color = Hairline, thickness = .7.dp)
                models.forEach { model ->
                    PopupChoiceRow(
                        title = modelName(model),
                        subtitle = modelDescription(model),
                        selected = selectedModelId == model.id,
                        onClick = {
                            selectedModelId = model.id
                            temperature = model.defaultTemperature.toFloat()
                            onChange(configuration.copy(modelId = model.id, temperature = model.defaultTemperature))
                            page = ModelPopupPage.Configuration
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ModelBadge(
    configuration: ModelConfiguration?,
    onMissingConfiguration: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(KcodeRadius.card))
                .background(if (hovered) Hairline.copy(alpha = .7f) else Mist)
                .hoverable(interaction)
                .pressScale(interaction, PressScaleStyle.Panel)
                .clickable(interactionSource = interaction, indication = null) {
                    if (configuration == null) onMissingConfiguration() else expanded = true
                }
                .padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(6.dp).clip(CircleShape)
                    .background(if (configuration == null) SoftInk.copy(.35f) else Leaf),
            )
            Text(
                modelOption(configuration?.modelId)?.let { modelName(it) } ?: text(UiText.SelectModel),
                Modifier.padding(start = KcodeSpacing.xs),
                color = SoftInk,
                style = MaterialTheme.typography.labelSmall,
            )
            Text("⌄", Modifier.padding(start = KcodeSpacing.hair), color = SoftInk, style = MaterialTheme.typography.labelSmall)
        }
        if (configuration != null) {
            ModelConfigurationBubble(
                expanded = expanded,
                configuration = configuration,
                placement = BubblePlacement.Below,
                onDismissRequest = { expanded = false },
                onChange = onConfigurationChange,
            )
        }
    }
}
