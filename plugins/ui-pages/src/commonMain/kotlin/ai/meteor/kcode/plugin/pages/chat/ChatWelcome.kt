@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.plugin.pages.chat

import ai.meteor.kcode.plugin.pages.chat.component.KcodeMark

import ai.meteor.kcode.ui.design.Mist
import ai.meteor.kcode.ui.design.Panel
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline


import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.MessageContentRequest
import androidx.compose.runtime.key
import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.MessageRole
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.ui.component.BubblePlacement
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.meteor.kcode.ui.component.KcodeHazeState
import ai.meteor.kcode.ui.component.kcodeHazeSource
import kotlinx.coroutines.delay
@Composable
fun Welcome(
    modifier: Modifier,
    compact: Boolean,
    hazeState: KcodeHazeState,
    configuration: ModelConfiguration?,
    setupMessage: String?,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onSend: (String) -> Unit,
    onModelClick: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
    composerActions: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    if (compact) {
        val density = LocalDensity.current
        var composerHeightPx by remember { mutableStateOf(0) }
        val composerHeight = with(density) { composerHeightPx.toDp() }
        Box(modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().kcodeHazeSource(hazeState)) {
                Box(Modifier.fillMaxSize().background(Paper))
                Box(
                    Modifier.fillMaxSize()
                        .padding(start = 12.dp, end = 12.dp, bottom = composerHeight)
                        .pointerInput(Unit) {
                            detectTapGestures { focusManager.clearFocus(force = true) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        KcodeMark(size = 52.dp)
                        Text(
                            text(UiText.WelcomeTitle),
                            Modifier.padding(top = KcodeSpacing.lg),
                            color = Ink,
                            style = MaterialTheme.typography.displaySmall,
                            textAlign = TextAlign.Center,
                        )
                        if (configuration == null || setupMessage != null) {
                            Text(
                                setupMessage ?: text(UiText.WelcomeBody),
                                Modifier.padding(top = KcodeSpacing.sm, start = KcodeSpacing.lg, end = KcodeSpacing.lg),
                                color = SoftInk,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { composerHeightPx = it.height }
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                MobileComposer(
                    modifier = Modifier.fillMaxWidth(),
                    hazeState = hazeState,
                    configuration = configuration,
                    generating = false,
                    replying = false,
                    focusRequester = focusRequester,
                    onFocus = onFocus,
                    onModelClick = onModelClick,
                    onConfigurationChange = onConfigurationChange,
                    onSend = onSend,
                    onStop = {},
                    composerActions = composerActions,
                )
            }
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().kcodeHazeSource(hazeState)) {
            Box(Modifier.fillMaxSize().background(Paper))
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            KcodeMark(size = 58.dp)
            Text(
                text(UiText.WelcomeTitle),
                Modifier.padding(top = KcodeSpacing.lg),
                color = Ink,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
            Text(
                setupMessage ?: text(UiText.WelcomeBody),
                Modifier.padding(top = KcodeSpacing.xs, bottom = KcodeSpacing.xl),
                color = SoftInk,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Composer(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(.78f),
                hazeState = hazeState,
                generating = false,
                focusRequester = focusRequester,
                onFocus = onFocus,
                onSend = onSend,
                onStop = {},
                composerActions = composerActions,
            )
            Row(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(.78f).padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val sendSuggestion: (String) -> Unit = {
                    focusManager.clearFocus(force = true)
                    onSend(it)
                }
                Suggestion(text(UiText.SuggestionIdea), Modifier.weight(1f), sendSuggestion)
                Suggestion(text(UiText.SuggestionCode), Modifier.weight(1f), sendSuggestion)
                Suggestion(text(UiText.SuggestionPlan), Modifier.weight(1f), sendSuggestion)
            }
        }
    }
}

@Composable
private fun Suggestion(label: String, modifier: Modifier, onClick: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier.clip(RoundedCornerShape(KcodeRadius.control))
            .background(if (hovered) Hairline.copy(alpha = .7f) else Mist)
            .hoverable(interaction)
            .pressScale(interaction, PressScaleStyle.Panel)
            .clickable(interactionSource = interaction, indication = null) { onClick(label) }
            .padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = SoftInk, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
