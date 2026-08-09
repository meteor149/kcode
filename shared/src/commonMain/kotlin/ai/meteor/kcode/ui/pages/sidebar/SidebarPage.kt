@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.sidebar

import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.SidebarPaper
import ai.meteor.kcode.ui.design.PaleMint
import ai.meteor.kcode.ui.design.LeafInk
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Error

import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing

import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.pressScale
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.meteor.kcode.ui.state.ConversationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun SidebarScaffold(
    width: Dp,
    sidebarOpen: Boolean,
    conversations: List<ConversationState>,
    activeId: Long?,
    onSidebarOpenChange: (Boolean) -> Unit,
    onNew: () -> Unit,
    onSelect: (Long) -> Unit,
    onPin: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: () -> Unit,
    content: @Composable (Modifier, Boolean) -> Unit,
) {
    val compact = width < 760.dp
    if (!compact) {
        Row(Modifier.fillMaxSize().background(Paper)) {
            Sidebar(
                conversations = conversations,
                activeId = activeId,
                compact = false,
                width = 276.dp,
                onNew = onNew,
                onSelect = onSelect,
                onPin = onPin,
                onDelete = onDelete,
                onSettings = onSettings,
            )
            content(Modifier.weight(1f), false)
        }
        return
    }

    val scope = rememberCoroutineScope()
    val drawerEndGap = if (width < 360.dp) 72.dp else 88.dp
    val drawerWidth = minOf(340.dp, (width - drawerEndGap).coerceAtLeast(0.dp))
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    val swipeDismissThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val drawerProgress = remember { Animatable(0f) }
    var isDrawerDragging by remember { mutableStateOf(false) }
    var draggedProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(sidebarOpen, drawerWidth, isDrawerDragging) {
        if (!isDrawerDragging) {
            drawerProgress.animateTo(
                targetValue = if (sidebarOpen) 1f else 0f,
                animationSpec = tween(320),
            )
        }
    }

    val visibleDrawerProgress = if (isDrawerDragging) draggedProgress else drawerProgress.value
    val contentOffset = drawerWidth * visibleDrawerProgress
    val contentRadius = 38.dp * visibleDrawerProgress
    val contentShadow = 18.dp * visibleDrawerProgress
    Box(Modifier.fillMaxSize().background(SidebarPaper)) {
        AnimatedVisibility(
            visible = sidebarOpen,
            modifier = Modifier.align(Alignment.CenterStart),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150)),
        ) {
            Sidebar(
                conversations = conversations,
                activeId = activeId,
                compact = true,
                width = drawerWidth,
                onNew = onNew,
                onSelect = {
                    onSelect(it)
                    onSidebarOpenChange(false)
                },
                onPin = onPin,
                onDelete = onDelete,
                onSettings = onSettings,
            )
        }
        Box(
            Modifier.fillMaxSize().offset(x = contentOffset)
                .shadow(contentShadow, RoundedCornerShape(contentRadius))
                .clip(RoundedCornerShape(contentRadius))
                .background(Paper),
        ) {
            content(Modifier.fillMaxSize(), true)
            if (sidebarOpen) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.White.copy(alpha = .52f * visibleDrawerProgress))
                        .pointerInput(swipeDismissThreshold, drawerWidthPx) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    draggedProgress = drawerProgress.value
                                    isDrawerDragging = true
                                    scope.launch { drawerProgress.stop() }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    if (drawerWidthPx > 0f) {
                                        change.consume()
                                        draggedProgress = (
                                            draggedProgress + dragAmount / drawerWidthPx
                                        ).coerceIn(0f, 1f)
                                    }
                                },
                                onDragEnd = {
                                    val shouldRemainOpen =
                                        (1f - draggedProgress) * drawerWidthPx < swipeDismissThreshold
                                    scope.launch {
                                        drawerProgress.snapTo(draggedProgress)
                                        onSidebarOpenChange(shouldRemainOpen)
                                        isDrawerDragging = false
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        drawerProgress.snapTo(draggedProgress)
                                        isDrawerDragging = false
                                    }
                                },
                            )
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSidebarOpenChange(false) },
                        ),
                )
            }
        }
    }
}

@Composable
internal fun Sidebar(
    conversations: List<ConversationState>,
    activeId: Long?,
    compact: Boolean,
    width: Dp,
    onNew: () -> Unit,
    onSelect: (Long) -> Unit,
    onPin: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.requiredWidth(width)
            .fillMaxHeight()
            .background(SidebarPaper)
            .padding(horizontal = if (compact) KcodeSpacing.sm else KcodeSpacing.md)
            .navigationBarsPadding(),
    ) {
        Text(
            "kcode",
            Modifier.padding(start = 5.dp, top = if (compact) 44.dp else 20.dp, bottom = 27.dp),
            color = Ink,
            fontFamily = FontFamily.Serif,
            fontSize = if (compact) 29.sp else 26.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = (-0.6).sp,
        )
        SidebarDestination(SidebarIcon.Chats, text(UiText.Chats))
        SidebarDestination(SidebarIcon.Artifacts, text(UiText.Artifacts))
        Text(
            text(UiText.Recent),
            Modifier.padding(top = KcodeSpacing.lg, bottom = KcodeSpacing.xs, start = KcodeSpacing.hair),
            color = SoftInk,
            style = MaterialTheme.typography.labelLarge,
        )

        if (conversations.isEmpty()) {
            Text(
                text(UiText.EmptyConversations),
                Modifier.padding(horizontal = KcodeSpacing.xs, vertical = KcodeSpacing.xs),
                color = SoftInk,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 90.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        title = conversation.title,
                        selected = conversation.id == activeId,
                        pinned = conversation.isPinned,
                        onClick = { onSelect(conversation.id) },
                        onPin = { onPin(conversation.id) },
                        onDelete = { onDelete(conversation.id) },
                    )
                }
            }
        }
        SidebarBottomActions(onNew = onNew, onSettings = onSettings)
    }
}

private enum class SidebarIcon { Chats, Artifacts }

@Composable
private fun SidebarDestination(icon: SidebarIcon, label: String) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = KcodeSpacing.hair),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
            SidebarLineIcon(icon)
        }
        Text(label, Modifier.padding(start = KcodeSpacing.xs), color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun SidebarLineIcon(icon: SidebarIcon) {
    KcodeIcon(
        asset = if (icon == SidebarIcon.Chats) KcodeIconAsset.Chat else KcodeIconAsset.Artifacts,
        tint = Ink,
        modifier = Modifier.size(25.dp),
    )
}

@Composable
private fun SidebarBottomActions(onNew: () -> Unit, onSettings: () -> Unit) {
    val settingsDescription = text(UiText.OpenSettings)
    val settingsInteraction = remember { MutableInteractionSource() }
    val newChatInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(top = KcodeSpacing.xs, bottom = KcodeSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onSettings,
            modifier = Modifier.pressScale(settingsInteraction, PressScaleStyle.Button)
                .size(KcodeSize.touchTarget)
                .semantics { contentDescription = settingsDescription; role = Role.Button },
            interactionSource = settingsInteraction,
            shape = CircleShape,
            color = Color.White,
            border = BorderStroke(1.dp, Hairline),
            shadowElevation = 5.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                KcodeIcon(KcodeIconAsset.Settings, Ink, Modifier.size(24.dp))
            }
        }
        Surface(
            onClick = onNew,
            modifier = Modifier.pressScale(newChatInteraction, PressScaleStyle.Button)
                .width(136.dp).height(KcodeSize.touchTarget),
            interactionSource = newChatInteraction,
            shape = RoundedCornerShape(KcodeRadius.panel),
            color = Ink,
            shadowElevation = 7.dp,
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = KcodeSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                KcodeIcon(KcodeIconAsset.Add, Color.White, Modifier.size(22.dp))
                Text(
                    text(UiText.NewChat),
                    Modifier.padding(start = KcodeSpacing.xs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    title: String,
    selected: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val density = LocalDensity.current
    val actionWidthPx = with(density) { 82.dp.toPx() }
    val actionThresholdPx = with(density) { 48.dp.toPx() }
    val restingOffset = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    val visibleOffset = if (dragging) dragOffset else restingOffset.value
    val rowShape = RoundedCornerShape(KcodeRadius.control)

    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(rowShape),
    ) {
        SwipeConversationAction(
            modifier = Modifier.fillMaxHeight().width(82.dp).align(Alignment.CenterStart),
            label = text(if (pinned) UiText.UnpinConversation else UiText.PinConversation),
            color = PaleMint,
            contentColor = LeafInk,
            isPin = true,
        )
        SwipeConversationAction(
            modifier = Modifier.fillMaxHeight().width(82.dp).align(Alignment.CenterEnd),
            label = text(UiText.DeleteConversation),
            color = Color(0xFFF7E8E6),
            contentColor = Error,
            isPin = false,
        )
        Row(
            Modifier.fillMaxSize()
                .offset { IntOffset(visibleOffset.roundToInt(), 0) }
                .clip(rowShape)
                .background(
                    if (selected) Color(0xFFEAEAE8)
                    else if (hovered) Color.White.copy(alpha = .72f)
                    else SidebarPaper,
                )
                .hoverable(interaction)
                .pressScale(interaction, PressScaleStyle.Panel)
                .pointerInput(actionWidthPx, actionThresholdPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragOffset = restingOffset.value
                            dragging = true
                            scope.launch { restingOffset.stop() }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset = (dragOffset + dragAmount).coerceIn(-actionWidthPx, actionWidthPx)
                        },
                        onDragEnd = {
                            val completedOffset = dragOffset
                            val delete = completedOffset <= -actionThresholdPx
                            val pin = completedOffset >= actionThresholdPx
                            scope.launch {
                                restingOffset.snapTo(completedOffset)
                                dragging = false
                                restingOffset.animateTo(
                                    when {
                                        delete -> -actionWidthPx
                                        pin -> actionWidthPx
                                        else -> 0f
                                    },
                                    animationSpec = tween(190, easing = FastOutSlowInEasing),
                                )
                                when {
                                    pin -> {
                                        delay(45)
                                        restingOffset.animateTo(0f, tween(145, easing = FastOutSlowInEasing))
                                        onPin()
                                    }
                                    delete -> onDelete()
                                    else -> restingOffset.snapTo(0f)
                                }
                            }
                        },
                        onDragCancel = {
                            val cancelledOffset = dragOffset
                            scope.launch {
                                restingOffset.snapTo(cancelledOffset)
                                dragging = false
                                restingOffset.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                            }
                        },
                    )
                }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = KcodeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                Modifier.weight(1f),
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pinned) {
                PinnedConversationIcon(Modifier.padding(start = KcodeSpacing.xs).size(14.dp))
            }
        }
    }
}

@Composable
private fun SwipeConversationAction(
    modifier: Modifier,
    label: String,
    color: Color,
    contentColor: Color,
    isPin: Boolean,
) {
    Row(
        modifier.background(color).padding(horizontal = KcodeSpacing.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KcodeIcon(
            if (isPin) KcodeIconAsset.Pin else KcodeIconAsset.Delete,
            contentColor,
            Modifier.size(14.dp),
        )
        Text(
            label,
            Modifier.padding(start = 4.dp),
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun PinnedConversationIcon(modifier: Modifier = Modifier) {
    KcodeIcon(KcodeIconAsset.Pin, LeafInk, modifier)
}
