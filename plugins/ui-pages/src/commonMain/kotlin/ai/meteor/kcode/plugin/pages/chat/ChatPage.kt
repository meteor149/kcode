@file:OptIn(ExperimentalMaterial3Api::class, kotlin.time.ExperimentalTime::class)

package ai.meteor.kcode.plugin.pages.chat

import ai.meteor.kcode.plugin.ui.api.SettingsEditorProjection
import ai.meteor.kcode.plugin.pages.chat.component.LocalChatHazeState
import ai.meteor.kcode.plugin.pages.chat.component.ConversationMessageList
import ai.meteor.kcode.plugin.pages.chat.component.StreamScrollFollower
import ai.meteor.kcode.plugin.pages.chat.component.animateToConversationBottom
import ai.meteor.kcode.plugin.pages.chat.component.isAtConversationBottom
import ai.meteor.kcode.plugin.pages.chat.component.latestContentIndex
import ai.meteor.kcode.plugin.pages.chat.component.rememberMessageSelectionState
import ai.meteor.kcode.plugin.pages.chat.component.scrollToConversationBottom

import androidx.compose.runtime.key
import ai.meteor.kcode.plugin.ui.api.PresentConversationContributions
import ai.meteor.kcode.plugin.ui.api.LocalApplicationUiSlots
import ai.meteor.kcode.plugin.ui.api.ConversationDecorationPosition
import ai.meteor.kcode.plugin.ui.api.ConversationPageContext
import ai.meteor.kcode.localization.LocalAppLanguage
import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.Error

import ai.meteor.kcode.chat.ConversationExecution
import ai.meteor.kcode.chat.ChatFailureMessages
import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.GoalSessionFactory
import ai.meteor.kcode.chat.ScheduledTaskCoordinator
import ai.meteor.kcode.ui.state.ConversationState
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.component.FloatingCircleButton
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import ai.meteor.kcode.ui.component.kcodeHazeSource
import ai.meteor.kcode.ui.component.rememberKcodeHazeState
@Composable
internal fun ChatPane(
    modifier: Modifier,
    compact: Boolean,
    conversation: ConversationState?,
    conversationExecution: ConversationExecution?,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    configuration: ModelConfiguration?,
    onConfigurationChange: (ModelConfiguration) -> Unit,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    onNewConversation: () -> Unit,
    onSendToNew: (String) -> ConversationState,
    historyRepository: ConversationHistoryRepository,
    goalSessionFactory: GoalSessionFactory,
    scheduledTaskCoordinator: ScheduledTaskCoordinator,
    settingsEditor: SettingsEditorProjection?,
) {
    val hazeState = rememberKcodeHazeState()
    val scope = rememberCoroutineScope()
    val streamScrollFollower = remember { StreamScrollFollower() }
    val connectionFailedMessage = text(UiText.ModelConnectionFailed)
    val setupModelMessage = text(UiText.SetupModelFirst)
    val failureMessages = ChatFailureMessages(
        setupModel = setupModelMessage,
        connectionFailed = connectionFailedMessage,
    )
    val language = LocalAppLanguage.current
    val listState = rememberLazyListState()
    val listIsDragged by listState.interactionSource.collectIsDraggedAsState()
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val messageSelection = rememberMessageSelectionState(conversation?.id)
    var mobileComposerHeightPx by remember { mutableStateOf(0) }
    var mobileBottomContributionsHeightPx by remember { mutableStateOf(0) }
    var anchoredTurn by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // Text fields and keyboard actions can retain an earlier callback instance. Keep the
    // callback stable while making every invocation observe the latest recomposed state.
    val currentConfiguration by rememberUpdatedState(configuration)
    val currentConversation by rememberUpdatedState(conversation)
    val regenerateDescription = text(UiText.RegenerateAnswer)
    val shareDescription = text(ai.meteor.kcode.localization.LocalizedText("select_messages"))
    val conversationContentMotion = rememberConversationContentMotion(conversation?.id)
    val messageAnchorTop = if (compact) 92.dp else 30.dp
    fun isAtConversationBottom(): Boolean {
        val target = currentConversation ?: return true
        return listState.isAtConversationBottom(target.latestContentIndex())
    }

    LaunchedEffect(listIsDragged) {
        if (listIsDragged) {
            streamScrollFollower.stopFollowing()
        } else if (isAtConversationBottom()) {
            streamScrollFollower.followLatest = true
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress to
                isAtConversationBottom()
        }.collect { (isScrollInProgress, isAtBottom) ->
            streamScrollFollower.onScrollStateChanged(
                isScrollInProgress = isScrollInProgress,
                isAtBottom = isAtBottom,
            )
        }
    }

    fun followConversationBottom(target: ConversationState) {
        val shouldScroll = streamScrollFollower.shouldScrollProgrammatically()
        if (target.messages.isEmpty() || !shouldScroll) return
        streamScrollFollower.job?.cancel()
        streamScrollFollower.job = scope.launch {
            val trailingRow = if (target.isGenerating && target.isAwaitingFirstToken) 1 else 0
            listState.scrollToConversationBottom(targetIndex = target.messages.lastIndex + trailingRow)
        }
    }

    val moreMenu = remember(conversation?.id, messageSelection.active) { ChatMoreMenuState() }
    val contributionContext = ConversationPageContext(
        conversation, compact, configuration, service, generationRunner,
        scheduledTaskCoordinator, failureMessages, ::followConversationBottom, hazeState,
        selectedMessageIds = if (messageSelection.active) messageSelection.ids.toSet() else null,
        clearSelection = messageSelection::clear,
        beforeAction = { focusManager.clearFocus(force = true) },
        settingsEditor = settingsEditor,
        moreMenu = moreMenu,
    )
    val pageSlots = LocalApplicationUiSlots.current
    val contributions = PresentConversationContributions(contributionContext, pageSlots)
    val decorations = contributions.filter { it.second.position == ConversationDecorationPosition.Header }
    val composerContributions = contributions.filter { it.second.position == ConversationDecorationPosition.AboveComposer }
    val headerActions = contributions.filter { it.second.position == ConversationDecorationPosition.HeaderActions }
    val actions: @Composable () -> Unit = {
        headerActions.forEach { (owner, content) ->
            key(owner, content.position) { content.renderer.Render(Modifier) }
        }
    }
    val moreActions: @Composable () -> Unit = {
        contributions.filter { it.second.position == ConversationDecorationPosition.MoreActions }
            .forEach { (owner, content) ->
                key(owner, content.position) { content.renderer.Render(Modifier) }
            }
    }
    val composerActions: @Composable () -> Unit = {
        contributions.filter { it.second.position == ConversationDecorationPosition.ComposerActions }
            .forEach { (owner, content) ->
                key(owner, content.position) { content.renderer.Render(Modifier) }
            }
    }
    val decorationsHeight = decorations.fold(0.dp) { total, decoration -> total + decoration.second.occupiedHeight }

    fun send(prompt: String) {
        conversationExecution?.sendMessage(
            prompt = prompt,
            configuration = currentConfiguration,
            conversation = currentConversation,
            onSendToNew = onSendToNew,
            service = service,
            generationRunner = generationRunner,
            goalSessionFactory = goalSessionFactory,
            scope = scope,
            failureMessages = failureMessages,
            language = language,
            scheduledTaskSessionFor = { target ->
                scheduledTaskCoordinator.sessionFor(target.id, target.title)
            },
            onUserMessageAdded = { target, message ->
                anchoredTurn = target.id to message.id
                streamScrollFollower.stopFollowing()
                streamScrollFollower.job = scope.launch {
                    withFrameNanos { }
                    withFrameNanos { }
                    val thinkingRow = if (target.isAwaitingFirstToken) 1 else 0
                    val tailSpaceIndex = target.messages.size + thinkingRow
                    listState.scrollToConversationBottom(targetIndex = tailSpaceIndex)
                }
            },
            followBottom = ::followConversationBottom,
        )
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
    }

    fun regenerate(answer: ChatMessage) {
        conversationExecution?.regenerateMessage(
            answer = answer,
            configuration = currentConfiguration,
            conversation = currentConversation,
            service = service,
            generationRunner = generationRunner,
            goalSessionFactory = goalSessionFactory,
            scope = scope,
            failureMessages = failureMessages,
            scheduledTaskSession = currentConversation?.let { target ->
                scheduledTaskCoordinator.sessionFor(target.id, target.title)
            },
            shouldFollowLatest = isAtConversationBottom(),
            onFollowLatestChange = { streamScrollFollower.followLatest = it },
            followBottom = ::followConversationBottom,
        )
    }

    fun beginMessageSelection(message: ChatMessage) {
        focusManager.clearFocus(force = true)
        messageSelection.begin(message)
    }

    LaunchedEffect(conversation?.id) {
        val count = conversation?.messages?.size ?: 0
        if (count > 0 && anchoredTurn?.first != conversation?.id) {
            streamScrollFollower.followLatest = true
            val lastIndex = conversation?.latestContentIndex() ?: -1
            listState.scrollToConversationBottom(targetIndex = lastIndex)
        }
    }

    CompositionLocalProvider(LocalChatHazeState provides hazeState) {
    if (compact) {
        BoxWithConstraints(modifier.fillMaxSize().background(Paper)) {
            val extraCompact = maxWidth < 360.dp
            val horizontalContentPadding = if (extraCompact) 12.dp else 20.dp
            val composerOuterPadding = if (extraCompact) 8.dp else 12.dp
            val listTopPadding = (if (extraCompact) 84.dp else 92.dp) +
                decorationsHeight
            val composerBottomPadding = if (mobileComposerHeightPx == 0) {
                176.dp
            } else {
                with(density) { mobileComposerHeightPx.toDp() } + 18.dp
            } + if (composerContributions.isEmpty()) 0.dp else {
                with(density) { mobileBottomContributionsHeightPx.toDp() } + 10.dp
            }
            val composerOverlayBottom = if (mobileComposerHeightPx == 0) {
                150.dp
            } else {
                with(density) { mobileComposerHeightPx.toDp() } + 4.dp
            }
            val centeredContent = Modifier.align(Alignment.Center)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .then(conversationContentMotion)
            if (conversation == null || conversation.messages.isEmpty()) {
                Welcome(
                    modifier = centeredContent,
                    compact = true,
                    hazeState = hazeState,
                    configuration = configuration,
                    setupMessage = if (configuration == null) text(UiText.SetupModelFirst)
                        else null,
                    focusRequester = focusRequester,
                    onFocus = {},
                    onSend = ::send,
                    onModelClick = onSettings,
                    onConfigurationChange = onConfigurationChange,
                    composerActions = composerActions,
                )
            } else {
                ConversationMessageList(
                    modifier = centeredContent.kcodeHazeSource(hazeState),
                    compact = true,
                    conversation = conversation,
                    listState = listState,
                    contentPadding = PaddingValues(
                        start = horizontalContentPadding,
                        end = horizontalContentPadding,
                        top = listTopPadding,
                        bottom = composerBottomPadding,
                    ),
                    itemSpacing = if (extraCompact) KcodeSpacing.lg else KcodeSpacing.xl,
                    configurationAvailable = configuration != null,
                    selectionMode = messageSelection.active,
                    selectedMessageIds = messageSelection.ids,
                    regenerateDescription = regenerateDescription,
                    shareDescription = shareDescription,
                    onBackgroundTap = { focusManager.clearFocus(force = true) },
                    onToggleSelection = messageSelection::toggle,
                    onShare = ::beginMessageSelection,
                    onRegenerate = { message ->
                        focusManager.clearFocus(force = true)
                        regenerate(message)
                    },
                    anchoredUserMessageId = anchoredTurn
                        ?.takeIf { it.first == conversation.id }
                        ?.second,
                    messageAnchorTop = messageAnchorTop,
                )
                if (composerContributions.isNotEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .padding(start = composerOuterPadding, end = composerOuterPadding, bottom = composerOverlayBottom)
                            .onSizeChanged { mobileBottomContributionsHeightPx = it.height },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        composerContributions.forEach { (owner, content) ->
                            key(owner, content.position) { content.renderer.Render(Modifier.fillMaxWidth()) }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = !isAtConversationBottom(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(
                        bottom = composerOverlayBottom - KcodeSize.floatingShadowGutter +
                            if (composerContributions.isEmpty()) 0.dp else {
                                with(density) { mobileBottomContributionsHeightPx.toDp() } + 8.dp
                            },
                    ),
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(140)),
                ) {
                    Box(Modifier.padding(KcodeSize.floatingShadowGutter)) {
                        FloatingCircleButton(
                            description = "Scroll to latest message",
                            onClick = {
                                focusManager.clearFocus(force = true)
                                streamScrollFollower.followLatest = true
                                scope.launch {
                                    val lastIndex = conversation.messages.lastIndex + if (conversation.isGenerating) 1 else 0
                                    listState.animateToConversationBottom(targetIndex = lastIndex)
                                }
                            },
                            size = 46.dp,
                        ) { KcodeIcon(KcodeIconAsset.ScrollDown, Ink, Modifier.size(22.dp)) }
                    }
                }
                MobileComposer(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .onSizeChanged { mobileComposerHeightPx = it.height }
                        .padding(horizontal = composerOuterPadding, vertical = 10.dp),
                    hazeState = hazeState,
                    configuration = configuration,
                    generating = conversation.isGenerating,
                    replying = true,
                    focusRequester = focusRequester,
                    onFocus = {},
                    onModelClick = onSettings,
                    onConfigurationChange = onConfigurationChange,
                    onSend = ::send,
                    onStop = { conversation.runningJob?.cancel() },
                    composerActions = composerActions,
                )
                conversation?.executionFailure?.let { notice ->
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = composerOverlayBottom + 8.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = .92f),
                        shadowElevation = 8.dp,
                    ) {
                        Text(
                            notice,
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 320.dp),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            CompactChatHeader(
                modifier = Modifier.align(Alignment.TopCenter),
                hazeState = hazeState,
                selectionMode = messageSelection.active,
                selectedCount = messageSelection.count,
                onCancelSelection = messageSelection::clear,
                actions = actions,
                onMenu = {
                    focusManager.clearFocus(force = true)
                    onMenu()
                },
                onNew = {
                    focusManager.clearFocus(force = true)
                    onNewConversation()
                },
                moreMenu = moreMenu,
                moreActions = moreActions,
            )
            if (decorations.isNotEmpty()) {
                Column(Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 16.dp, end = 16.dp)) {
                    decorations.forEach { (owner, content) ->
                        key(owner, content.position) { content.renderer.Render(Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    } else {
        Column(modifier.fillMaxHeight().background(Paper)) {
            conversation?.executionFailure?.let { failure ->
                Text(failure, Modifier.fillMaxWidth().padding(KcodeSpacing.md),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
            DesktopChatHeader(
                title = conversation?.title ?: text(UiText.NewChat),
                selectionMode = messageSelection.active,
                selectedCount = messageSelection.count,
                configuration = configuration,
                onMenu = {
                    focusManager.clearFocus(force = true)
                    onMenu()
                },
                onCancelSelection = messageSelection::clear,
                moreMenu = moreMenu,
                moreActions = moreActions,
                actions = actions,
                onMissingConfiguration = {
                    focusManager.clearFocus(force = true)
                    onSettings()
                },
                onConfigurationChange = onConfigurationChange,
            )
            decorations.forEach { (owner, content) ->
                key(owner, content.position) {
                    content.renderer.Render(Modifier.align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp, vertical = 8.dp).widthIn(max = 760.dp).fillMaxWidth())
                }
            }
            if (conversation == null || conversation.messages.isEmpty()) {
                Welcome(
                    modifier = Modifier.weight(1f).then(conversationContentMotion),
                    compact = false,
                    hazeState = hazeState,
                    configuration = configuration,
                    setupMessage = if (configuration == null) text(UiText.SetupModelFirst)
                        else null,
                    focusRequester = focusRequester,
                    onFocus = {},
                    onSend = ::send,
                    onModelClick = onSettings,
                    onConfigurationChange = onConfigurationChange,
                    composerActions = composerActions,
                )
            } else {
                ConversationMessageList(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .kcodeHazeSource(hazeState)
                        .then(conversationContentMotion),
                    compact = false,
                    conversation = conversation,
                    listState = listState,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 30.dp),
                    itemSpacing = 30.dp,
                    configurationAvailable = configuration != null,
                    selectionMode = messageSelection.active,
                    selectedMessageIds = messageSelection.ids,
                    regenerateDescription = regenerateDescription,
                    shareDescription = shareDescription,
                    onBackgroundTap = { focusManager.clearFocus(force = true) },
                    onToggleSelection = messageSelection::toggle,
                    onShare = ::beginMessageSelection,
                    onRegenerate = { message ->
                        focusManager.clearFocus(force = true)
                        regenerate(message)
                    },
                    anchoredUserMessageId = anchoredTurn
                        ?.takeIf { it.first == conversation.id }
                        ?.second,
                    messageAnchorTop = messageAnchorTop,
                )
                if (composerContributions.isNotEmpty()) {
                    Column(
                        Modifier.align(Alignment.CenterHorizontally).widthIn(max = 760.dp)
                            .fillMaxWidth().padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        composerContributions.forEach { (owner, content) ->
                            key(owner, content.position) { content.renderer.Render(Modifier.fillMaxWidth()) }
                        }
                    }
                }
                Composer(                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    hazeState = hazeState,
                    generating = conversation.isGenerating,
                    focusRequester = focusRequester,
                    onFocus = {},
                    onSend = ::send,
                    onStop = { conversation.runningJob?.cancel() },
                    composerActions = composerActions,
                )
            }
        }
    }
    }

}

@Composable
private fun rememberConversationContentMotion(conversationId: Long?): Modifier {
    val density = LocalDensity.current
    val transition = remember { Animatable(1f) }
    var lastConversationId by remember { mutableStateOf(conversationId) }
    val distancePx = with(density) { KcodeSpacing.md.toPx() }

    LaunchedEffect(conversationId) {
        if (lastConversationId != conversationId) {
            lastConversationId = conversationId
            transition.snapTo(0f)
            transition.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            )
        }
    }

    return Modifier.graphicsLayer {
        val progress = transition.value
        alpha = progress
        translationX = (1f - progress) * distancePx
    }
}
