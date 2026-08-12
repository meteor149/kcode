@file:OptIn(ExperimentalMaterial3Api::class)

package ai.meteor.kcode.ui.pages.chat

import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.Error

import ai.meteor.kcode.chat.ChatService
import ai.meteor.kcode.chat.ChatGenerationRunner
import ai.meteor.kcode.chat.ConversationGoalSession
import ai.meteor.kcode.ui.state.ConversationState
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.component.FloatingCircleButton
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset

import ai.meteor.kcode.model.ChatMessage
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.history.ThreadGoalStatus
import ai.meteor.kcode.history.ThreadGoal
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.localization.availabilityError
import ai.meteor.kcode.localization.availabilityStatus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import ai.meteor.kcode.ui.component.kcodeHazeSource
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.component.rememberKcodeHazeState
@Composable
internal fun ChatPane(
    modifier: Modifier,
    compact: Boolean,
    conversation: ConversationState?,
    service: ChatService,
    generationRunner: ChatGenerationRunner,
    configuration: ModelConfiguration?,
    onConfigurationChange: (ModelConfiguration) -> Unit,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    onNewConversation: () -> Unit,
    onSendToNew: (String) -> ConversationState,
    historyRepository: ConversationHistoryRepository,
    imageSaver: ConversationImageSaver,
    toolPermissionControlsAvailable: Boolean,
    toolPermissionMode: ToolPermissionMode,
    onToolPermissionModeChange: (ToolPermissionMode) -> Unit,
) {
    val hazeState = rememberKcodeHazeState()
    val scope = rememberCoroutineScope()
    val streamScrollFollower = remember { StreamScrollFollower() }
    val connectionFailedMessage = text(UiText.ModelConnectionFailed)
    val setupModelMessage = text(UiText.SetupModelFirst)
    val unavailableMessage = service.availability?.let { availabilityError(it) }
    val failureMessages = ChatFailureMessages(
        setupModel = setupModelMessage,
        connectionFailed = connectionFailedMessage,
        unavailable = unavailableMessage,
    )
    val goalLabel = text(UiText.Goal)
    val goalStatusLabel = text(UiText.GoalStatus)
    val goalTokensLabel = text(UiText.GoalTokens)
    val goalPauseAction = text(UiText.GoalPauseAction)
    val goalResumeAction = text(UiText.GoalResumeAction)
    val goalCancelAction = text(UiText.GoalCancelAction)
    val goalStatusNames = mapOf(
        ThreadGoalStatus.Active to text(UiText.GoalActive),
        ThreadGoalStatus.Paused to text(UiText.GoalPaused),
        ThreadGoalStatus.Blocked to text(UiText.GoalBlocked),
        ThreadGoalStatus.UsageLimited to text(UiText.GoalUsageLimited),
        ThreadGoalStatus.BudgetLimited to text(UiText.GoalBudgetLimited),
        ThreadGoalStatus.Complete to text(UiText.GoalComplete),
    )
    val goalMessages = GoalCommandMessages(
        noGoal = text(UiText.GoalNoGoal),
        cleared = text(UiText.GoalCleared),
        objectiveRequired = text(UiText.GoalObjectiveRequired),
        summarize = { goal ->
            buildString {
                append(goalLabel).append(": ").append(goal.objective)
                append('\n').append(goalStatusLabel).append(": ").append(goalStatusNames.getValue(goal.status))
                append('\n').append(goalTokensLabel).append(": ").append(goal.tokensUsed)
                goal.tokenBudget?.let { append(" / ").append(it) }
            }
        },
    )
    val listState = rememberLazyListState()
    val listIsDragged by listState.interactionSource.collectIsDraggedAsState()
    val focusRequester = remember { FocusRequester() }
    val exportState = rememberChatExportState(imageSaver)
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val messageSelection = rememberMessageSelectionState(conversation?.id)
    var mobileComposerHeightPx by remember { mutableStateOf(0) }
    var mobileAgentOverlayHeightPx by remember { mutableStateOf(0) }
    var anchoredTurn by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // Text fields and keyboard actions can retain an earlier callback instance. Keep the
    // callback stable while making every invocation observe the latest recomposed state.
    val currentConfiguration by rememberUpdatedState(configuration)
    val currentConversation by rememberUpdatedState(conversation)
    val regenerateDescription = text(UiText.RegenerateAnswer)
    val shareDescription = text(UiText.ShareImage)
    val conversationContentMotion = rememberConversationContentMotion(conversation?.id)
    val visibleGoal = conversation?.goal?.takeUnless { it.status == ThreadGoalStatus.Complete }
    val runningSubAgents = conversation?.messages
        ?.flatMap(ChatMessage::subAgents)
        ?.associateBy { it.path }
        ?.values
        ?.filter { it.status.isRunning() }
        .orEmpty()
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

    LaunchedEffect(conversation?.id, configuration) {
        conversation?.let { target ->
            continueRestoredGoal(
                target = target,
                configuration = configuration,
                service = service,
                generationRunner = generationRunner,
                historyRepository = historyRepository,
                failureMessages = failureMessages,
                followBottom = ::followConversationBottom,
            )
        }
    }

    fun send(prompt: String) {
        sendMessage(
            prompt = prompt,
            configuration = currentConfiguration,
            conversation = currentConversation,
            onSendToNew = onSendToNew,
            service = service,
            generationRunner = generationRunner,
            historyRepository = historyRepository,
            scope = scope,
            failureMessages = failureMessages,
            goalMessages = goalMessages,
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
    }

    fun pauseGoal() {
        val target = currentConversation ?: return
        target.shouldResumeGoal = false
        target.runningJob?.cancel()
        scope.launch {
            target.runningJob?.join()
            val current = target.goal ?: return@launch
            if (current.status != ThreadGoalStatus.Paused) {
                ConversationGoalSession(target, historyRepository).setStatusFromUser(ThreadGoalStatus.Paused)
            }
        }
    }

    fun resumeGoal() {
        val target = currentConversation ?: return
        if (target.isGenerating) return
        scope.launch {
            ConversationGoalSession(target, historyRepository).setStatusFromUser(ThreadGoalStatus.Active)
            target.shouldResumeGoal = true
            continueRestoredGoal(
                target = target,
                configuration = currentConfiguration,
                service = service,
                generationRunner = generationRunner,
                historyRepository = historyRepository,
                failureMessages = failureMessages,
                followBottom = ::followConversationBottom,
            )
        }
    }

    fun cancelGoal() {
        val target = currentConversation ?: return
        target.shouldResumeGoal = false
        target.runningJob?.cancel()
        scope.launch {
            target.runningJob?.join()
            if (target.goal != null) ConversationGoalSession(target, historyRepository).clearGoal()
        }
    }

    fun regenerate(answer: ChatMessage) {
        regenerateMessage(
            answer = answer,
            configuration = currentConfiguration,
            conversation = currentConversation,
            service = service,
            generationRunner = generationRunner,
            historyRepository = historyRepository,
            scope = scope,
            failureMessages = failureMessages,
            shouldFollowLatest = isAtConversationBottom(),
            onFollowLatestChange = { streamScrollFollower.followLatest = it },
            followBottom = ::followConversationBottom,
        )
    }

    fun exportConversation(action: ExportAction, selectedIds: Set<Long>? = null) {
        exportState.export(
            action = action,
            conversation = currentConversation,
            configuration = currentConfiguration,
            selectedIds = selectedIds,
        )
    }

    fun beginMessageSelection(message: ChatMessage) {
        focusManager.clearFocus(force = true)
        messageSelection.begin(message)
    }

    fun exportSelectedMessages(action: ExportAction) {
        if (messageSelection.isEmpty) return
        val selection = messageSelection.ids
        messageSelection.clear()
        exportConversation(action, selection)
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
                if (visibleGoal == null) 0.dp else 54.dp
            val composerBottomPadding = if (mobileComposerHeightPx == 0) {
                176.dp
            } else {
                with(density) { mobileComposerHeightPx.toDp() } + 18.dp
            } + if (runningSubAgents.isEmpty()) 0.dp else {
                with(density) { mobileAgentOverlayHeightPx.toDp() } + 10.dp
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
                        else service.availability?.let { availabilityStatus(it) },
                    focusRequester = focusRequester,
                    onFocus = {},
                    onSend = ::send,
                    onModelClick = onSettings,
                    onConfigurationChange = onConfigurationChange,
                    toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                    toolPermissionMode = toolPermissionMode,
                    onToolPermissionModeChange = onToolPermissionModeChange,
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
                if (runningSubAgents.isNotEmpty()) {
                    RunningSubAgentOverlay(
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .padding(
                                start = composerOuterPadding,
                                end = composerOuterPadding,
                                bottom = composerOverlayBottom,
                            )
                            .onSizeChanged { mobileAgentOverlayHeightPx = it.height },
                        agents = runningSubAgents,
                        hazeState = hazeState,
                    )
                }
                AnimatedVisibility(
                    visible = !isAtConversationBottom(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(
                        bottom = composerOverlayBottom - KcodeSize.floatingShadowGutter +
                            if (runningSubAgents.isEmpty()) 0.dp else {
                                with(density) { mobileAgentOverlayHeightPx.toDp() } + 8.dp
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
                    toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                    toolPermissionMode = toolPermissionMode,
                    onToolPermissionModeChange = onToolPermissionModeChange,
                )
                exportState.notice?.let { notice ->
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = composerOverlayBottom + 8.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = Ink.copy(alpha = .92f),
                        shadowElevation = 8.dp,
                    ) {
                        Text(
                            notice,
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 320.dp),
                            color = Color.White,
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
                onSaveSelection = { exportSelectedMessages(ExportAction.Save) },
                onShareSelection = { exportSelectedMessages(ExportAction.Share) },
                onMenu = {
                    focusManager.clearFocus(force = true)
                    onMenu()
                },
                onNew = {
                    focusManager.clearFocus(force = true)
                    onNewConversation()
                },
                exportEnabled = !conversation?.messages.isNullOrEmpty(),
                onExportSave = {
                    focusManager.clearFocus(force = true)
                    exportConversation(ExportAction.Save)
                },
                onExportShare = {
                    focusManager.clearFocus(force = true)
                    exportConversation(ExportAction.Share)
                },
            )
            visibleGoal?.let { goal ->
                GoalStatusBanner(
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                        .widthIn(max = 720.dp)
                        .fillMaxWidth(),
                    goal = goal,
                    statusName = goalStatusNames.getValue(goal.status),
                    goalLabel = goalLabel,
                    tokensLabel = goalTokensLabel,
                    pauseLabel = goalPauseAction,
                    resumeLabel = goalResumeAction,
                    cancelLabel = goalCancelAction,
                    onPause = ::pauseGoal,
                    onResume = ::resumeGoal,
                    onCancel = ::cancelGoal,
                )
            }
        }
    } else {
        Column(modifier.fillMaxHeight().background(Paper)) {
            DesktopChatHeader(
                conversationId = conversation?.id,
                title = conversation?.title ?: text(UiText.NewChat),
                hasMessages = !conversation?.messages.isNullOrEmpty(),
                selectionMode = messageSelection.active,
                selectedCount = messageSelection.count,
                configuration = configuration,
                onMenu = {
                    focusManager.clearFocus(force = true)
                    onMenu()
                },
                onCancelSelection = messageSelection::clear,
                onSaveSelection = { exportSelectedMessages(ExportAction.Save) },
                onShareSelection = { exportSelectedMessages(ExportAction.Share) },
                onSaveConversation = {
                    focusManager.clearFocus(force = true)
                    exportConversation(ExportAction.Save)
                },
                onShareConversation = {
                    focusManager.clearFocus(force = true)
                    exportConversation(ExportAction.Share)
                },
                onMissingConfiguration = {
                    focusManager.clearFocus(force = true)
                    onSettings()
                },
                onConfigurationChange = onConfigurationChange,
            )
            visibleGoal?.let { goal ->
                GoalStatusBanner(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .widthIn(max = 760.dp)
                        .fillMaxWidth(),
                    goal = goal,
                    statusName = goalStatusNames.getValue(goal.status),
                    goalLabel = goalLabel,
                    tokensLabel = goalTokensLabel,
                    pauseLabel = goalPauseAction,
                    resumeLabel = goalResumeAction,
                    cancelLabel = goalCancelAction,
                    onPause = ::pauseGoal,
                    onResume = ::resumeGoal,
                    onCancel = ::cancelGoal,
                )
            }
            if (conversation == null || conversation.messages.isEmpty()) {
                Welcome(
                    modifier = Modifier.weight(1f).then(conversationContentMotion),
                    compact = false,
                    hazeState = hazeState,
                    configuration = configuration,
                    setupMessage = if (configuration == null) text(UiText.SetupModelFirst)
                        else service.availability?.let { availabilityStatus(it) },
                    focusRequester = focusRequester,
                    onFocus = {},
                    onSend = ::send,
                    onModelClick = onSettings,
                    onConfigurationChange = onConfigurationChange,
                    toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                    toolPermissionMode = toolPermissionMode,
                    onToolPermissionModeChange = onToolPermissionModeChange,
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
                if (runningSubAgents.isNotEmpty()) {
                    RunningSubAgentOverlay(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .widthIn(max = 760.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        agents = runningSubAgents,
                        hazeState = hazeState,
                    )
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
                    toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                    toolPermissionMode = toolPermissionMode,
                    onToolPermissionModeChange = onToolPermissionModeChange,
                )
            }
        }
    }
    }

}

@Composable
private fun GoalStatusBanner(
    modifier: Modifier,
    goal: ThreadGoal,
    statusName: String,
    goalLabel: String,
    tokensLabel: String,
    pauseLabel: String,
    resumeLabel: String,
    cancelLabel: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    var expanded by remember(goal.goalId) { mutableStateOf(false) }
    Surface(
        modifier = modifier.animateContentSize()
            .pressClickable(style = PressScaleStyle.Panel) { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$goalLabel · $statusName",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (!expanded) {
                    Text(
                        text = goal.objective,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
                Text(
                    text = buildString {
                        append(tokensLabel).append(' ').append(goal.tokensUsed)
                        goal.tokenBudget?.let { append('/').append(it) }
                    },
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                )
                KcodeIcon(
                    KcodeIconAsset.ChevronDown,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
                )
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
                Column(Modifier.fillMaxWidth().padding(top = KcodeSpacing.sm)) {
                    Text(
                        text = goal.objective,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = KcodeSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.sm),
                    ) {
                        when (goal.status) {
                            ThreadGoalStatus.Active -> GoalActionButton(
                                modifier = Modifier.weight(1f),
                                label = pauseLabel,
                                onClick = {
                                    expanded = false
                                    onPause()
                                },
                            )
                            ThreadGoalStatus.Complete -> Unit
                            else -> GoalActionButton(
                                modifier = Modifier.weight(1f),
                                label = resumeLabel,
                                onClick = {
                                    expanded = false
                                    onResume()
                                },
                            )
                        }
                        GoalActionButton(
                            modifier = Modifier.weight(1f),
                            label = cancelLabel,
                            destructive = true,
                            onClick = {
                                expanded = false
                                onCancel()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalActionButton(
    modifier: Modifier,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.pressClickable(style = PressScaleStyle.Button, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (destructive) MaterialTheme.colorScheme.errorContainer else {
            MaterialTheme.colorScheme.surface.copy(alpha = .68f)
        },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
            color = if (destructive) Error else MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
        )
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
