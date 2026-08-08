@file:OptIn(ExperimentalMaterial3Api::class)

package app.kcode

import app.kcode.model.ChatMessage
import app.kcode.model.MessageRole
import app.kcode.model.ToolUseInfo
import app.kcode.model.ToolUseStatus
import app.kcode.model.decodeStoredMessageContent
import app.kcode.model.toStoredContent
import app.kcode.model.ModelConfiguration
import app.kcode.model.ModelOption
import app.kcode.model.ModelProvider
import app.kcode.model.conversationTitle
import app.kcode.model.modelOption
import app.kcode.model.modelsFor
import app.kcode.model.requiresApiKey
import app.kcode.model.requiresDeployment
import app.kcode.model.requiresEndpoint
import app.kcode.model.requiresRegion
import app.kcode.history.ConversationHistoryRepository
import app.kcode.history.StoredMessage
import app.kcode.history.TransientConversationHistoryRepository
import app.kcode.export.ConversationImageSaver
import app.kcode.export.UnsupportedConversationImageSaver
import app.kcode.export.ConversationExportMessage
import app.kcode.export.ImageSaveResult
import app.kcode.export.renderConversationImage
import app.kcode.settings.AppSettingsStore
import app.kcode.settings.StoredAppSettings
import app.kcode.settings.ShellExecutionMode
import app.kcode.settings.ToolPermissionMode
import app.kcode.settings.TransientAppSettingsStore
import app.kcode.search.WebSearchProvider
import app.kcode.ui.AnchoredBubblePopup
import app.kcode.ui.BottomSheetOverlay
import app.kcode.ui.BubblePlacement
import app.kcode.ui.KcodeRadius
import app.kcode.ui.KcodeSize
import app.kcode.ui.KcodeSpacing
import app.kcode.ui.KcodeTypography
import app.kcode.ui.PressScaleStyle
import app.kcode.ui.pressClickable
import app.kcode.ui.pressScale
import app.kcode.localization.AppLanguage
import app.kcode.localization.LocalAppLanguage
import app.kcode.localization.UiText
import app.kcode.localization.modelDescription
import app.kcode.localization.modelName
import app.kcode.localization.providerName
import app.kcode.localization.providerNote
import app.kcode.localization.text
import app.kcode.localization.resolveText
import app.kcode.localization.availabilityError
import app.kcode.localization.availabilityStatus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kcode.composeapp.generated.resources.Res
import kcode.composeapp.generated.resources.kcode_mark
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val Mist = Color(0xCCF1F1EF)
private val Panel = Color(0xFFF4F4F2)
private val Paper = Color.White
private val SidebarPaper = Color(0xFFF7F7F5)
private val Mint = Color(0xFFBCE8CC)
private val PaleMint = Color(0xFFEBF8EF)
private val Leaf = Color(0xFF8FD6A8)
private val LeafInk = Color(0xFF3E7653)
private val Ink = Color(0xFF202622)
private val SoftInk = Color(0xFF727570)
private val Hairline = Color(0xFFE4E5E2)
private val Error = Color(0xFF9B403C)

@Composable
fun KcodeApp(
    chatService: ChatService,
    settingsStore: AppSettingsStore = TransientAppSettingsStore,
    historyRepository: ConversationHistoryRepository = TransientConversationHistoryRepository,
    imageSaver: ConversationImageSaver = UnsupportedConversationImageSaver,
    shellSettingsAvailable: Boolean = false,
    toolPermissionControlsAvailable: Boolean = false,
    onShellExecutionModeChanged: (ShellExecutionMode) -> Unit = {},
    onToolPermissionModeChanged: (ToolPermissionMode) -> Unit = {},
) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Leaf,
            onPrimary = Ink,
            primaryContainer = PaleMint,
            onPrimaryContainer = Ink,
            background = Paper,
            surface = Paper,
            onSurface = Ink,
        ),
        typography = KcodeTypography,
    ) {
        KcodeWorkspace(
            chatService,
            settingsStore,
            historyRepository,
            imageSaver,
            shellSettingsAvailable,
            toolPermissionControlsAvailable,
            onShellExecutionModeChanged,
            onToolPermissionModeChanged,
        )
    }
}

@Composable
private fun KcodeWorkspace(
    chatService: ChatService,
    settingsStore: AppSettingsStore,
    historyRepository: ConversationHistoryRepository,
    imageSaver: ConversationImageSaver,
    shellSettingsAvailable: Boolean,
    toolPermissionControlsAvailable: Boolean,
    onShellExecutionModeChanged: (ShellExecutionMode) -> Unit,
    onToolPermissionModeChanged: (ToolPermissionMode) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val conversations = remember { mutableStateListOf<ConversationState>() }
    var activeId by remember { mutableStateOf<Long?>(null) }
    var sidebarOpen by remember { mutableStateOf(false) }
    var sequence by remember { mutableStateOf(1L) }
    var appSettings by remember { mutableStateOf(StoredAppSettings()) }
    var configuration by remember { mutableStateOf<ModelConfiguration?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var persistenceFailure by remember { mutableStateOf<PersistenceFailure?>(null) }
    var settingsRevision by remember { mutableStateOf(0L) }

    LaunchedEffect(settingsStore) {
        val revisionAtLoadStart = settingsRevision
        runCatching { settingsStore.load() }
            .onSuccess { stored ->
                // Do not let a slow startup read replace settings the user has just saved.
                if (settingsRevision == revisionAtLoadStart) {
                    appSettings = stored
                    configuration = stored.toModelConfiguration()
                    onShellExecutionModeChanged(ShellExecutionMode.fromCode(stored.shellExecutionMode))
                    onToolPermissionModeChanged(ToolPermissionMode.fromCode(stored.toolPermissionMode))
                }
            }
            .onFailure { persistenceFailure = PersistenceFailure(reading = true, it.message) }
    }

    LaunchedEffect(historyRepository) {
        runCatching { historyRepository.loadAll() }
            .onSuccess { storedConversations ->
                if (conversations.isEmpty()) {
                    conversations += storedConversations.map { stored ->
                        ConversationState(stored.id, stored.title, stored.isPinned).apply {
                            messages += stored.messages.map(StoredMessage::toChatMessage)
                        }
                    }
                    activeId = storedConversations.firstOrNull()?.id
                }
                sequence = maxOf(sequence, (storedConversations.maxOfOrNull { it.id } ?: 0L) + 1L)
            }
    }

    fun updateSettings(value: StoredAppSettings) {
        settingsRevision += 1L
        appSettings = value
        configuration = value.toModelConfiguration()
        persistenceFailure = null
        scope.launch {
            runCatching { settingsStore.save(value) }
                .onFailure { persistenceFailure = PersistenceFailure(reading = false, it.message) }
        }
    }

    fun updateConfiguration(value: ModelConfiguration) = updateSettings(appSettings.withConfiguration(value))

    fun newConversation() {
        activeId = null
        sidebarOpen = false
        settingsOpen = false
    }

    fun ensureConversation(prompt: String): ConversationState {
        val current = conversations.firstOrNull { it.id == activeId }
        if (current != null) return current
        val created = ConversationState(sequence++, conversationTitle(prompt))
        val firstUnpinned = conversations.indexOfFirst { !it.isPinned }
        conversations.add(if (firstUnpinned < 0) conversations.size else firstUnpinned, created)
        activeId = created.id
        return created
    }

    fun pinConversation(id: Long) {
        val index = conversations.indexOfFirst { it.id == id }
        if (index < 0) return
        val conversation = conversations.removeAt(index)
        conversation.isPinned = true
        conversations.add(0, conversation)
        scope.launch { runCatching { historyRepository.setPinned(id, true) } }
    }

    fun deleteConversation(id: Long) {
        val index = conversations.indexOfFirst { it.id == id }
        if (index < 0) return
        val conversation = conversations.removeAt(index)
        if (activeId == id) activeId = null
        scope.launch {
            conversation.runningJob?.let { job ->
                job.cancel()
                job.join()
            }
            runCatching { historyRepository.deleteConversation(id) }
        }
    }

    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.fromCode(appSettings.language)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Paper)
                .safeDrawingPadding(),
        ) {
            BoxWithResponsiveWidth { width ->
                val compact = width < 760.dp
                val mainContent: @Composable (Modifier, Boolean) -> Unit = { contentModifier, isCompact ->
                    val active = conversations.firstOrNull { it.id == activeId }
                    ChatPane(
                        modifier = contentModifier,
                        compact = isCompact,
                        conversation = active,
                        service = chatService,
                        configuration = configuration,
                        onConfigurationChange = ::updateConfiguration,
                        onMenu = { sidebarOpen = true },
                        onSettings = { settingsOpen = true },
                        onNewConversation = ::newConversation,
                        onSendToNew = { prompt -> ensureConversation(prompt) },
                        historyRepository = historyRepository,
                        imageSaver = imageSaver,
                        toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                        toolPermissionMode = ToolPermissionMode.fromCode(appSettings.toolPermissionMode),
                        onToolPermissionModeChange = {
                            onToolPermissionModeChanged(it)
                            updateSettings(appSettings.copy(toolPermissionMode = it.code))
                        },
                    )
                }

                if (compact) {
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
                                onNew = ::newConversation,
                                onSelect = { activeId = it; sidebarOpen = false },
                                onPin = ::pinConversation,
                                onDelete = ::deleteConversation,
                                onSettings = { settingsOpen = true },
                            )
                        }
                        Box(
                            Modifier.fillMaxSize().offset(x = contentOffset)
                                .shadow(contentShadow, RoundedCornerShape(contentRadius))
                                .clip(RoundedCornerShape(contentRadius))
                                .background(Paper),
                        ) {
                            mainContent(Modifier.fillMaxSize(), true)
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
                                                        sidebarOpen = shouldRemainOpen
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
                                            onClick = { sidebarOpen = false },
                                        ),
                                )
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxSize().background(Paper)) {
                        Sidebar(
                            conversations = conversations,
                            activeId = activeId,
                            compact = false,
                            width = 276.dp,
                            onNew = ::newConversation,
                            onSelect = { activeId = it },
                            onPin = ::pinConversation,
                            onDelete = ::deleteConversation,
                            onSettings = { settingsOpen = true },
                        )
                        mainContent(Modifier.weight(1f), false)
                    }
                }
            }
            if (settingsOpen) {
                SettingsDialog(
                    current = configuration,
                    persistenceFailure = persistenceFailure,
                    language = AppLanguage.fromCode(appSettings.language),
                    shellSettingsAvailable = shellSettingsAvailable,
                    shellExecutionMode = ShellExecutionMode.fromCode(appSettings.shellExecutionMode),
                    webSearchApiKey = appSettings.webSearchApiKey,
                    exaSearchApiKey = appSettings.exaSearchApiKey,
                    webSearchProvider = WebSearchProvider.fromCode(appSettings.webSearchProvider),
                    onLanguageChange = { updateSettings(appSettings.copy(language = it.code)) },
                    onShellExecutionModeChange = {
                        onShellExecutionModeChanged(it)
                        updateSettings(appSettings.copy(shellExecutionMode = it.code))
                    },
                    onWebSearchSettingsSave = { searchProvider, brightDataKey, exaKey ->
                        updateSettings(appSettings.copy(
                            webSearchProvider = searchProvider.code,
                            webSearchApiKey = brightDataKey,
                            exaSearchApiKey = exaKey,
                        ))
                    },
                    onSave = { saved ->
                        val provider = saved.provider
                        val existingModel = configuration?.modelId
                            ?.let(::modelOption)
                            ?.takeIf { it.provider == provider }
                        val model = existingModel ?: modelsFor(provider).first()
                        updateConfiguration(saved.copy(
                            modelId = model.id,
                            temperature = if (existingModel != null) configuration?.temperature ?: model.defaultTemperature else model.defaultTemperature,
                        ))
                    },
                    onDismiss = { settingsOpen = false },
                )
            }
        }
    }
}

private fun StoredAppSettings.toModelConfiguration(): ModelConfiguration? {
    val parsedProvider = ModelProvider.entries.firstOrNull { it.name == provider } ?: return null
    val selectedModel = modelOption(modelId)?.takeIf { it.provider == parsedProvider }
        ?: modelsFor(parsedProvider).firstOrNull()
        ?: return null
    if (parsedProvider.requiresApiKey && apiKey.isBlank()) return null
    if (parsedProvider.requiresEndpoint && modelEndpoint.isBlank()) return null
    if (parsedProvider.requiresRegion && modelRegion.isBlank()) return null
    if (parsedProvider.requiresDeployment && modelDeployment.isBlank()) return null
    return ModelConfiguration(
        provider = parsedProvider,
        modelId = selectedModel.id,
        apiKey = apiKey,
        temperature = temperature.coerceIn(0.0, 1.0),
        endpoint = modelEndpoint,
        region = modelRegion,
        deployment = modelDeployment,
        apiVersion = modelApiVersion,
    )
}

private fun StoredAppSettings.withConfiguration(value: ModelConfiguration) = copy(
    provider = value.provider.name,
    modelId = value.modelId,
    apiKey = value.apiKey,
    temperature = value.temperature,
    modelEndpoint = value.endpoint,
    modelRegion = value.region,
    modelDeployment = value.deployment,
    modelApiVersion = value.apiVersion,
)

private data class PersistenceFailure(val reading: Boolean, val detail: String?)

private class ConversationState(val id: Long, initialTitle: String, initialPinned: Boolean = false) {
    var title by mutableStateOf(initialTitle)
    var isPinned by mutableStateOf(initialPinned)
    val messages = mutableStateListOf<ChatMessage>()
    var isGenerating by mutableStateOf(false)
    var isAwaitingFirstToken by mutableStateOf(false)
    var runningJob: Job? = null
}

/** Keeps presentation-only scrolling out of the model request coroutine. */
private class StreamScrollFollower {
    var job: Job? = null
    var followLatest: Boolean = true
}

private fun StoredMessage.toChatMessage(): ChatMessage {
    val (messageText, toolUses) = decodeStoredMessageContent(content)
    return ChatMessage(
        id = id,
        role = if (role == MessageRole.User.name) MessageRole.User else MessageRole.Assistant,
        content = messageText,
        isError = isError,
        toolUses = toolUses,
    )
}

@Composable
private fun Sidebar(
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
            .padding(horizontal = if (compact) KcodeSpacing.sm else KcodeSpacing.md),
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
    Canvas(Modifier.size(25.dp)) {
        val stroke = size.minDimension * .075f
        when (icon) {
            SidebarIcon.Chats -> {
                drawCircle(Ink, size.minDimension * .27f, Offset(size.width * .38f, size.height * .39f), style = Stroke(stroke))
                drawCircle(Ink, size.minDimension * .25f, Offset(size.width * .62f, size.height * .59f), style = Stroke(stroke))
                drawLine(Ink, Offset(size.width * .18f, size.height * .56f), Offset(size.width * .11f, size.height * .72f), stroke)
                drawLine(Ink, Offset(size.width * .11f, size.height * .72f), Offset(size.width * .31f, size.height * .66f), stroke)
                drawLine(Ink, Offset(size.width * .78f, size.height * .73f), Offset(size.width * .88f, size.height * .84f), stroke)
            }
            SidebarIcon.Artifacts -> {
                drawCircle(Ink, size.width * .17f, Offset(size.width * .27f, size.height * .63f), style = Stroke(stroke))
                drawCircle(Ink, size.width * .15f, Offset(size.width * .56f, size.height * .33f), style = Stroke(stroke))
                drawRoundRect(
                    Ink,
                    Offset(size.width * .58f, size.height * .56f),
                    Size(size.width * .29f, size.height * .3f),
                    CornerRadius(size.width * .04f),
                    style = Stroke(stroke),
                )
                drawLine(Ink, Offset(size.width * .39f, size.height * .52f), Offset(size.width * .48f, size.height * .4f), stroke)
                drawLine(Ink, Offset(size.width * .55f, size.height * .48f), Offset(size.width * .68f, size.height * .57f), stroke)
            }
        }
    }
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
            border = androidx.compose.foundation.BorderStroke(1.dp, Hairline),
            shadowElevation = 5.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                SettingsTuneIcon()
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
                Text("+", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Light)
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

/**
 * Runtime-safe rendering of the settings_tune.svg artwork. Compose Resources
 * keeps the SVG as the canonical asset, while drawing the same geometry here
 * avoids platform-specific SVG decoder failures when the drawer is composed.
 */
@Composable
private fun SettingsTuneIcon() {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        val knobRadius = 2.dp.toPx()
        val roundStroke = Stroke(width = stroke)

        fun rail(startX: Float, endX: Float, y: Float) {
            drawLine(
                color = Ink,
                start = Offset(startX.dp.toPx(), y.dp.toPx()),
                end = Offset(endX.dp.toPx(), y.dp.toPx()),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        fun knob(x: Float, y: Float) {
            val center = Offset(x.dp.toPx(), y.dp.toPx())
            drawCircle(color = Paper, radius = knobRadius, center = center)
            drawCircle(color = Ink, radius = knobRadius, center = center, style = roundStroke)
        }

        rail(4f, 8.25f, 6.75f)
        rail(12.25f, 20f, 6.75f)
        knob(10.25f, 6.75f)

        rail(4f, 14.25f, 12f)
        rail(18.25f, 20f, 12f)
        knob(16.25f, 12f)

        rail(4f, 6.25f, 17.25f)
        rail(10.25f, 20f, 17.25f)
        knob(8.25f, 17.25f)
    }
}

@Composable
private fun ModelBadge(
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

@Composable
private fun SettingsDialog(
    current: ModelConfiguration?,
    persistenceFailure: PersistenceFailure?,
    language: AppLanguage,
    shellSettingsAvailable: Boolean,
    shellExecutionMode: ShellExecutionMode,
    webSearchApiKey: String,
    exaSearchApiKey: String,
    webSearchProvider: WebSearchProvider,
    onLanguageChange: (AppLanguage) -> Unit,
    onShellExecutionModeChange: (ShellExecutionMode) -> Unit,
    onWebSearchSettingsSave: (WebSearchProvider, String, String) -> Unit,
    onSave: (ModelConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    var route by remember { mutableStateOf(SettingsRoute.Home) }
    var provider by remember(current) { mutableStateOf(current?.provider ?: ModelProvider.OpenAI) }
    var apiKey by remember(current) { mutableStateOf(current?.apiKey.orEmpty()) }
    var endpoint by remember(current) { mutableStateOf(current?.endpoint.orEmpty()) }
    var region by remember(current) { mutableStateOf(current?.region.orEmpty()) }
    var deployment by remember(current) { mutableStateOf(current?.deployment.orEmpty()) }
    var apiVersion by remember(current) { mutableStateOf(current?.apiVersion.orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var searchApiKey by remember(webSearchApiKey) { mutableStateOf(webSearchApiKey) }
    var exaApiKey by remember(exaSearchApiKey) { mutableStateOf(exaSearchApiKey) }
    var searchProvider by remember(webSearchProvider) { mutableStateOf(webSearchProvider) }
    var showSearchKey by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(route) {
        focusManager.clearFocus(force = true)
    }

    BottomSheetOverlay(onDismissRequest = onDismiss) { dismissSheet ->
        Column(Modifier.fillMaxSize()) {
            SettingsWindowHeader(
                title = when (route) {
                    SettingsRoute.Home -> text(UiText.Settings)
                    SettingsRoute.Language -> text(UiText.Language)
                    SettingsRoute.ModelService -> text(UiText.ModelService)
                    SettingsRoute.ShellExecution -> text(UiText.ShellExecution)
                    SettingsRoute.InternetSearch -> text(UiText.InternetSearch)
                },
                isRoot = route == SettingsRoute.Home,
                onNavigation = {
                    if (route == SettingsRoute.Home) dismissSheet() else route = SettingsRoute.Home
                },
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (route) {
                    SettingsRoute.Home -> SettingsHome(
                        language = language,
                        current = current,
                        onLanguage = { route = SettingsRoute.Language },
                        onModelService = { route = SettingsRoute.ModelService },
                        webSearchProvider = webSearchProvider,
                        onInternetSearch = { route = SettingsRoute.InternetSearch },
                        shellSettingsAvailable = shellSettingsAvailable,
                        shellExecutionMode = shellExecutionMode,
                        onShellExecution = { route = SettingsRoute.ShellExecution },
                    )

                    SettingsRoute.Language -> LanguageSettings(
                        language = language,
                        onLanguageChange = onLanguageChange,
                    )

                    SettingsRoute.ModelService -> ModelServiceSettings(
                        provider = provider,
                        apiKey = apiKey,
                        endpoint = endpoint,
                        region = region,
                        deployment = deployment,
                        apiVersion = apiVersion,
                        showKey = showKey,
                        persistenceFailure = persistenceFailure,
                        onProviderChange = {
                            provider = it
                            if (current?.provider != it) {
                                apiKey = ""
                                endpoint = if (it == ModelProvider.Ollama) "http://localhost:11434" else ""
                                region = if (it == ModelProvider.Bedrock) "us-west-2" else ""
                                deployment = ""
                                apiVersion = if (it == ModelProvider.AzureOpenAI) "2024-10-21" else ""
                            }
                        },
                        onApiKeyChange = { apiKey = it },
                        onEndpointChange = { endpoint = it },
                        onRegionChange = { region = it },
                        onDeploymentChange = { deployment = it },
                        onApiVersionChange = { apiVersion = it },
                        onToggleKey = { showKey = !showKey },
                        onSave = {
                            val model = modelsFor(provider).first()
                            onSave(ModelConfiguration(
                                provider = provider,
                                modelId = model.id,
                                apiKey = apiKey.trim(),
                                temperature = model.defaultTemperature,
                                endpoint = endpoint.trim(),
                                region = region.trim(),
                                deployment = deployment.trim(),
                                apiVersion = apiVersion.trim(),
                            ))
                            route = SettingsRoute.Home
                        },
                    )

                    SettingsRoute.ShellExecution -> ShellExecutionSettings(
                        selected = shellExecutionMode,
                        onSelected = onShellExecutionModeChange,
                    )

                    SettingsRoute.InternetSearch -> InternetSearchSettings(
                        provider = searchProvider,
                        brightDataApiKey = searchApiKey,
                        exaApiKey = exaApiKey,
                        showKey = showSearchKey,
                        onProviderChange = { searchProvider = it },
                        onBrightDataApiKeyChange = { searchApiKey = it },
                        onExaApiKeyChange = { exaApiKey = it },
                        onToggleKey = { showSearchKey = !showSearchKey },
                        onSave = {
                            onWebSearchSettingsSave(searchProvider, searchApiKey.trim(), exaApiKey.trim())
                            route = SettingsRoute.Home
                        },
                    )
                }
            }
        }
    }
}

private enum class SettingsRoute { Home, Language, ModelService, ShellExecution, InternetSearch }

@Composable
private fun SettingsWindowHeader(
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
                    drawLine(Ink, Offset(size.width * .2f, size.height * .2f), Offset(size.width * .8f, size.height * .8f), stroke, StrokeCap.Round)
                    drawLine(Ink, Offset(size.width * .8f, size.height * .2f), Offset(size.width * .2f, size.height * .8f), stroke, StrokeCap.Round)
                } else {
                    drawLine(Ink, Offset(size.width * .68f, size.height * .15f), Offset(size.width * .3f, size.height * .5f), stroke, StrokeCap.Round)
                    drawLine(Ink, Offset(size.width * .3f, size.height * .5f), Offset(size.width * .68f, size.height * .85f), stroke, StrokeCap.Round)
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
private fun SettingsHome(
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
private fun ShellExecutionSettings(
    selected: ShellExecutionMode,
    onSelected: (ShellExecutionMode) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            ShellModeRow(
                mode = ShellExecutionMode.App,
                description = text(UiText.ShellModeAppDescription),
                selected = selected == ShellExecutionMode.App,
                onClick = { onSelected(ShellExecutionMode.App) },
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            ShellModeRow(
                mode = ShellExecutionMode.Adb,
                description = text(UiText.ShellModeAdbDescription),
                selected = selected == ShellExecutionMode.Adb,
                onClick = { onSelected(ShellExecutionMode.Adb) },
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            ShellModeRow(
                mode = ShellExecutionMode.Root,
                description = text(UiText.ShellModeRootDescription),
                selected = selected == ShellExecutionMode.Root,
                onClick = { onSelected(ShellExecutionMode.Root) },
            )
        }
        SettingsWarningNotice(
            text(UiText.ShellExecutionWarning),
            Modifier.padding(top = KcodeSpacing.sm),
        )
    }
}

@Composable
private fun ShellModeRow(
    mode: ShellExecutionMode,
    description: String,
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
            when (mode) {
                ShellExecutionMode.App -> SecondarySettingsIcon.App
                ShellExecutionMode.Adb -> SecondarySettingsIcon.Adb
                ShellExecutionMode.Root -> SecondarySettingsIcon.Root
            },
            selected,
        )
        Column(Modifier.padding(start = KcodeSpacing.md).weight(1f)) {
            Text(shellModeTitle(mode), color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text(description, Modifier.padding(top = KcodeSpacing.hair), color = SoftInk, style = MaterialTheme.typography.bodySmall)
        }
        Text(if (selected) "✓" else "", color = LeafInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsWarningNotice(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KcodeRadius.card),
        color = Error.copy(alpha = .07f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(18.dp)) {
                val strokeWidth = 1.5.dp.toPx()
                drawCircle(Error.copy(alpha = .85f), size.minDimension * .42f, style = Stroke(strokeWidth))
                drawLine(
                    Error.copy(alpha = .85f),
                    Offset(size.width * .5f, size.height * .28f),
                    Offset(size.width * .5f, size.height * .57f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawCircle(Error.copy(alpha = .85f), size.minDimension * .045f, Offset(size.width * .5f, size.height * .72f))
            }
            Text(
                message,
                Modifier.padding(start = KcodeSpacing.sm).weight(1f),
                color = Error.copy(alpha = .9f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun shellModeTitle(mode: ShellExecutionMode): String = text(
    when (mode) {
        ShellExecutionMode.App -> UiText.ShellModeApp
        ShellExecutionMode.Adb -> UiText.ShellModeAdb
        ShellExecutionMode.Root -> UiText.ShellModeRoot
    },
)

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

private enum class SecondarySettingsIcon {
    Chinese, English,
    OpenAI, DeepSeek, Glm,
    Google, Exa, BrightData,
    App, Adb, Root,
}

@Composable
private fun SecondarySettingsIcon(icon: SecondarySettingsIcon, selected: Boolean) {
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
private fun LanguageSettings(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            SettingsChoiceRow(
                icon = SecondarySettingsIcon.Chinese,
                label = text(UiText.SimplifiedChinese),
                selected = language == AppLanguage.Chinese,
                onClick = { onLanguageChange(AppLanguage.Chinese) },
            )
            HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
            SettingsChoiceRow(
                icon = SecondarySettingsIcon.English,
                label = text(UiText.English),
                selected = language == AppLanguage.English,
                onClick = { onLanguageChange(AppLanguage.English) },
            )
        }
    }
}

@Composable
private fun SettingsChoiceRow(icon: SecondarySettingsIcon, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .pressClickable(onClick = onClick).clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = KcodeSpacing.hair, vertical = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondarySettingsIcon(icon, selected)
        Text(label, Modifier.padding(start = KcodeSpacing.md).weight(1f), color = Ink, style = MaterialTheme.typography.bodyLarge)
        Text(if (selected) "✓" else "", color = LeafInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InternetSearchSettings(
    provider: WebSearchProvider,
    brightDataApiKey: String,
    exaApiKey: String,
    showKey: Boolean,
    onProviderChange: (WebSearchProvider) -> Unit,
    onBrightDataApiKeyChange: (String) -> Unit,
    onExaApiKeyChange: (String) -> Unit,
    onToggleKey: () -> Unit,
    onSave: () -> Unit,
) {
    val saveInteraction = remember { MutableInteractionSource() }
    val saveEnabled = !provider.requiresApiKey ||
        (provider == WebSearchProvider.Exa && exaApiKey.isNotBlank()) ||
        (provider == WebSearchProvider.BrightData && brightDataApiKey.isNotBlank())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSectionLabel(text(UiText.SearchProvider))
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            WebSearchProvider.entries.forEachIndexed { index, item ->
                WebSearchProviderRow(item, provider == item) { onProviderChange(item) }
                if (index != WebSearchProvider.entries.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
                }
            }
        }
        if (provider.requiresApiKey) {
            val selectedKey = if (provider == WebSearchProvider.Exa) exaApiKey else brightDataApiKey
            CompactSectionLabel(text(UiText.ApiKey), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ApiKeyField(
                    selectedKey,
                    showKey,
                    if (provider == WebSearchProvider.Exa) onExaApiKeyChange else onBrightDataApiKeyChange,
                    onToggleKey,
                )
            }
        }
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.fillMaxWidth().padding(top = KcodeSpacing.md)
                .pressScale(saveInteraction, PressScaleStyle.Button, saveEnabled)
                .height(KcodeSize.touchTarget),
            interactionSource = saveInteraction,
            shape = RoundedCornerShape(KcodeRadius.card),
            colors = ButtonDefaults.buttonColors(
                containerColor = Leaf,
                disabledContainerColor = Mist,
                disabledContentColor = SoftInk,
            ),
        ) {
            Text(text(UiText.SaveSettings), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun WebSearchProviderRow(provider: WebSearchProvider, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .pressClickable(onClick = onClick).clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = KcodeSpacing.hair, vertical = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondarySettingsIcon(
            when (provider) {
                WebSearchProvider.Google -> SecondarySettingsIcon.Google
                WebSearchProvider.Exa -> SecondarySettingsIcon.Exa
                WebSearchProvider.BrightData -> SecondarySettingsIcon.BrightData
            },
            selected,
        )
        Column(Modifier.padding(start = KcodeSpacing.md).weight(1f)) {
            Text(provider.displayName(), color = Ink, style = MaterialTheme.typography.bodyLarge)
            Text(
                text(if (provider.requiresApiKey) UiText.ApiKeyRequired else UiText.NoApiKeyRequired),
                color = SoftInk,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(if (selected) "✓" else "", color = LeafInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WebSearchProvider.displayName(): String = when (this) {
    WebSearchProvider.Google -> "Google"
    WebSearchProvider.Exa -> "Exa"
    WebSearchProvider.BrightData -> "Bright Data"
}

@Composable
private fun ModelServiceSettings(
    provider: ModelProvider,
    apiKey: String,
    endpoint: String,
    region: String,
    deployment: String,
    apiVersion: String,
    showKey: Boolean,
    persistenceFailure: PersistenceFailure?,
    onProviderChange: (ModelProvider) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onRegionChange: (String) -> Unit,
    onDeploymentChange: (String) -> Unit,
    onApiVersionChange: (String) -> Unit,
    onToggleKey: () -> Unit,
    onSave: () -> Unit,
) {
    val saveInteraction = remember { MutableInteractionSource() }
    val saveEnabled = (!provider.requiresApiKey || apiKey.isNotBlank()) &&
        (!provider.requiresEndpoint || endpoint.isNotBlank()) &&
        (!provider.requiresRegion || region.isNotBlank()) &&
        (!provider.requiresDeployment || deployment.isNotBlank())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = KcodeSpacing.md),
    ) {
        CompactSectionLabel(text(UiText.Provider))
        CompactSettingsGroup(contentPadding = PaddingValues(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair)) {
            ModelProvider.entries.forEachIndexed { index, item ->
                CompactProviderRow(item, provider == item) { onProviderChange(item) }
                if (index != ModelProvider.entries.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = KcodeSpacing.xxl), thickness = .5.dp, color = Hairline)
                }
            }
        }
        if (provider.requiresApiKey) {
            CompactSectionLabel(text(UiText.ApiKey), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ApiKeyField(apiKey, showKey, onApiKeyChange, onToggleKey)
            }
        }
        if (provider.requiresEndpoint) {
            CompactSectionLabel(text(UiText.Endpoint), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(
                    value = endpoint,
                    placeholder = if (provider == ModelProvider.Ollama) "http://localhost:11434" else "https://…openai.azure.com",
                    onValueChange = onEndpointChange,
                )
            }
        }
        if (provider.requiresDeployment) {
            CompactSectionLabel(text(UiText.Deployment), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(deployment, text(UiText.DeploymentHint), onDeploymentChange)
            }
            CompactSectionLabel(text(UiText.ApiVersion), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(apiVersion, "2024-10-21", onApiVersionChange)
            }
        }
        if (provider.requiresRegion) {
            CompactSectionLabel(text(UiText.Region), Modifier.padding(top = KcodeSpacing.lg))
            CompactSettingsGroup {
                ConnectionTextField(region, "us-west-2", onRegionChange)
            }
        }
        persistenceFailure?.let {
            val detail = it.detail ?: text(UiText.UnknownError)
            Text(
                text(if (it.reading) UiText.ReadSettingsFailed else UiText.SaveSettingsFailed, detail),
                Modifier.padding(start = KcodeSpacing.xs, top = KcodeSpacing.sm, end = KcodeSpacing.xs),
                color = Error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.fillMaxWidth().padding(top = KcodeSpacing.md)
                .pressScale(saveInteraction, PressScaleStyle.Button, saveEnabled)
                .height(KcodeSize.touchTarget),
            interactionSource = saveInteraction,
            shape = RoundedCornerShape(KcodeRadius.card),
            colors = ButtonDefaults.buttonColors(
                containerColor = Leaf,
                disabledContainerColor = Mist,
                disabledContentColor = SoftInk,
            ),
        ) {
            Text(text(UiText.SaveSettings), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ConnectionTextField(
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
private fun CompactSectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier.padding(start = KcodeSpacing.sm, bottom = KcodeSpacing.xs),
        color = SoftInk,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun CompactSettingsGroup(
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
private fun CompactProviderRow(
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

private val ChoicePopupShape = RoundedCornerShape(KcodeRadius.panel)

@Composable
private fun KcodeBubblePopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    placement: BubblePlacement,
    modifier: Modifier = Modifier,
    minWidth: Dp = 248.dp,
    maxWidth: Dp = 300.dp,
    maxHeight: Dp = 620.dp,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnchoredBubblePopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        placement = placement,
        modifier = modifier,
        minWidth = minWidth,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        shape = ChoicePopupShape,
        surfaceColor = Color.White.copy(alpha = .985f),
        borderColor = Hairline.copy(alpha = .72f),
        focusable = focusable,
        content = content,
    )
}

@Composable
private fun PopupNavigationRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val action = if (onClick == null) Modifier else Modifier
        .hoverable(interaction)
        .pressScale(interaction, PressScaleStyle.Panel)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(KcodeRadius.control))
            .background(if (hovered && onClick != null) Panel.copy(alpha = .72f) else Color.Transparent)
            .then(action).padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
            if (!value.isNullOrBlank()) {
                Text(
                    value,
                    Modifier.padding(top = KcodeSpacing.hair),
                    color = SoftInk.copy(alpha = .72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Text("›", Modifier.padding(start = KcodeSpacing.sm), color = Ink, fontSize = 28.sp, lineHeight = 28.sp)
        }
    }
}

@Composable
private fun PopupSectionLabel(label: String) {
    Text(
        label,
        Modifier.fillMaxWidth().padding(
            start = KcodeSpacing.md,
            end = KcodeSpacing.md,
            top = KcodeSpacing.sm,
            bottom = KcodeSpacing.hair,
        ),
        color = SoftInk.copy(alpha = .78f),
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun PopupChoiceRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    showChevron: Boolean = false,
    titleColor: Color = Ink,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(KcodeRadius.control))
            .background(if (hovered) Panel.copy(alpha = .72f) else Color.Transparent)
            .hoverable(interaction)
            .pressScale(interaction, PressScaleStyle.Panel)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = KcodeSpacing.sm, end = KcodeSpacing.md, top = KcodeSpacing.xs, bottom = KcodeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(KcodeSpacing.xl), contentAlignment = Alignment.CenterStart) {
            if (selected) Text("✓", color = Ink, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    Modifier.padding(top = KcodeSpacing.hair),
                    color = SoftInk.copy(alpha = .74f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (showChevron) Text("›", Modifier.padding(start = KcodeSpacing.xs), color = Ink, fontSize = 26.sp, lineHeight = 26.sp)
    }
}

private enum class ModelPopupPage { Configuration, Models }

@Composable
private fun ModelConfigurationBubble(
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
private fun ApiKeyField(
    value: String,
    showKey: Boolean,
    onValueChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(KcodeRadius.control),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Hairline),
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
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                cursorBrush = SolidColor(Leaf),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(text(UiText.EnterApiKey), color = SoftInk.copy(.65f), style = MaterialTheme.typography.bodyMedium)
                        inner()
                    }
                },
            )
            Text(
                if (showKey) text(UiText.Hide) else text(UiText.Show),
                Modifier.pressClickable(style = PressScaleStyle.Button, onClick = onToggleVisibility)
                    .clip(RoundedCornerShape(KcodeSpacing.xs)).padding(KcodeSpacing.xs),
                color = SoftInk,
                style = MaterialTheme.typography.labelSmall,
            )
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
            label = text(UiText.PinConversation),
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
        Canvas(Modifier.size(14.dp)) {
            val stroke = size.minDimension * .12f
            if (isPin) {
                drawLine(contentColor, Offset(size.width * .28f, size.height * .28f), Offset(size.width * .72f, size.height * .72f), stroke, StrokeCap.Round)
                drawLine(contentColor, Offset(size.width * .47f, size.height * .16f), Offset(size.width * .84f, size.height * .53f), stroke, StrokeCap.Round)
                drawLine(contentColor, Offset(size.width * .2f, size.height * .6f), Offset(size.width * .55f, size.height * .25f), stroke, StrokeCap.Round)
                drawLine(contentColor, Offset(size.width * .35f, size.height * .65f), Offset(size.width * .14f, size.height * .86f), stroke, StrokeCap.Round)
            } else {
                drawRoundRect(
                    contentColor,
                    topLeft = Offset(size.width * .24f, size.height * .3f),
                    size = Size(size.width * .52f, size.height * .57f),
                    cornerRadius = CornerRadius(size.width * .08f),
                    style = Stroke(stroke),
                )
                drawLine(contentColor, Offset(size.width * .17f, size.height * .24f), Offset(size.width * .83f, size.height * .24f), stroke, StrokeCap.Round)
                drawLine(contentColor, Offset(size.width * .39f, size.height * .13f), Offset(size.width * .61f, size.height * .13f), stroke, StrokeCap.Round)
            }
        }
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
    Canvas(modifier) {
        val stroke = size.minDimension * .11f
        drawLine(LeafInk, Offset(size.width * .28f, size.height * .28f), Offset(size.width * .72f, size.height * .72f), stroke, StrokeCap.Round)
        drawLine(LeafInk, Offset(size.width * .47f, size.height * .16f), Offset(size.width * .84f, size.height * .53f), stroke, StrokeCap.Round)
        drawLine(LeafInk, Offset(size.width * .2f, size.height * .6f), Offset(size.width * .55f, size.height * .25f), stroke, StrokeCap.Round)
        drawLine(LeafInk, Offset(size.width * .35f, size.height * .65f), Offset(size.width * .14f, size.height * .86f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun ChatPane(
    modifier: Modifier,
    compact: Boolean,
    conversation: ConversationState?,
    service: ChatService,
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
    val scope = rememberCoroutineScope()
    val streamScrollFollower = remember { StreamScrollFollower() }
    val connectionFailedMessage = text(UiText.ModelConnectionFailed)
    val setupModelMessage = text(UiText.SetupModelFirst)
    val unavailableMessage = service.availability?.let { availabilityError(it) }
    val listState = rememberLazyListState()
    val listIsDragged by listState.interactionSource.collectIsDraggedAsState()
    val focusRequester = remember { FocusRequester() }
    val exportLayer = rememberGraphicsLayer()
    val exportTextMeasurer = rememberTextMeasurer()
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val focusManager = LocalFocusManager.current
    var exporting by remember { mutableStateOf(false) }
    var exportNotice by remember { mutableStateOf<String?>(null) }
    var desktopExportExpanded by remember(conversation?.id) { mutableStateOf(false) }
    var desktopSelectionExportExpanded by remember(conversation?.id) { mutableStateOf(false) }
    var messageSelectionMode by remember(conversation?.id) { mutableStateOf(false) }
    val selectedMessageIds = remember(conversation?.id) { mutableStateListOf<Long>() }
    var mobileComposerHeightPx by remember { mutableStateOf(0) }
    var keyboardSettleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Text fields and keyboard actions can retain an earlier callback instance. Keep the
    // callback stable while making every invocation observe the latest recomposed state.
    val currentConfiguration by rememberUpdatedState(configuration)
    val currentConversation by rememberUpdatedState(conversation)
    val currentLanguage = LocalAppLanguage.current
    val regenerateDescription = text(UiText.RegenerateAnswer)
    val shareDescription = text(UiText.ShareImage)
    val truncatedFooter = text(UiText.ExportTruncatedFooter)
    val exportingMessage = text(UiText.ExportingConversation)
    val unsupportedMessage = text(UiText.ExportUnsupported)
    val unknownError = text(UiText.UnknownError)
    val conversationContentTransition = remember { Animatable(1f) }
    var lastTransitionedConversationId by remember { mutableStateOf(conversation?.id) }
    val conversationTransitionDistancePx = with(density) { KcodeSpacing.md.toPx() }

    LaunchedEffect(conversation?.id) {
        if (lastTransitionedConversationId != conversation?.id) {
            lastTransitionedConversationId = conversation?.id
            conversationContentTransition.snapTo(0f)
            conversationContentTransition.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            )
        }
    }

    val conversationContentMotion = Modifier.graphicsLayer {
        val progress = conversationContentTransition.value
        alpha = progress
        translationX = (1f - progress) * conversationTransitionDistancePx
    }

    LaunchedEffect(listIsDragged) {
        if (listIsDragged) {
            // A real finger/mouse drag is an explicit request to inspect an earlier point.
            streamScrollFollower.followLatest = false
            streamScrollFollower.job?.cancel()
        } else if (listState.isAtConversationBottom(reverseLayout = compact)) {
            // Dragging back to the end opts in to following subsequent tokens again.
            streamScrollFollower.followLatest = true
        }
    }

    fun followConversationBottom(target: ConversationState) {
        if (!streamScrollFollower.followLatest || target.messages.isEmpty()) return
        streamScrollFollower.job?.cancel()
        streamScrollFollower.job = scope.launch {
            val trailingRow = if (target.isGenerating && target.isAwaitingFirstToken) 1 else 0
            listState.scrollToConversationBottom(
                reverseLayout = compact,
                targetIndex = target.messages.lastIndex + trailingRow,
            )
        }
    }

    fun send(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty()) return
        val activeConfiguration = currentConfiguration
        if (activeConfiguration == null) {
            val target = currentConversation ?: onSendToNew(clean)
            if (target.isGenerating) return
            streamScrollFollower.followLatest = currentConversation == null ||
                listState.isAtConversationBottom(reverseLayout = compact)
            val userMessage = ChatMessage(
                id = nextMessageId(target),
                role = MessageRole.User,
                content = clean,
            )
            val setupMessage = ChatMessage(
                id = userMessage.id + 1L,
                role = MessageRole.Assistant,
                content = setupModelMessage,
            )
            target.messages += userMessage
            target.messages += setupMessage
            followConversationBottom(target)
            scope.launch {
                runCatching {
                    historyRepository.appendMessage(
                        conversationId = target.id,
                        title = target.title,
                        messageId = userMessage.id,
                        role = userMessage.role.name,
                        content = userMessage.content,
                    )
                    historyRepository.appendMessage(
                        conversationId = target.id,
                        title = target.title,
                        messageId = setupMessage.id,
                        role = setupMessage.role.name,
                        content = setupMessage.content,
                    )
                }
            }
            return
        }
        val target = currentConversation ?: onSendToNew(clean)
        if (target.isGenerating) return
        streamScrollFollower.followLatest = currentConversation == null ||
            listState.isAtConversationBottom(reverseLayout = compact)
        val history = target.messages.toList()
        val userMessage = ChatMessage(id = nextMessageId(target), role = MessageRole.User, content = clean)
        target.messages += userMessage
        target.isGenerating = true
        target.isAwaitingFirstToken = true
        val assistantId = userMessage.id + 1L
        target.messages += ChatMessage(assistantId, MessageRole.Assistant, "")
        followConversationBottom(target)
        target.runningJob = scope.launch {
            runCatching {
                historyRepository.appendMessage(
                    conversationId = target.id,
                    title = target.title,
                    messageId = userMessage.id,
                    role = userMessage.role.name,
                    content = userMessage.content,
                )
            }
            try {
                val answer = service.replyStreaming(
                    configuration = activeConfiguration,
                    history = history,
                    prompt = clean,
                    onToolUse = { event ->
                        target.isAwaitingFirstToken = false
                        target.applyToolUseEvent(assistantId, event)
                        followConversationBottom(target)
                    },
                    onDelta = { delta ->
                        if (delta.isNotEmpty()) {
                            target.isAwaitingFirstToken = false
                            target.updateMessage(assistantId) { it.copy(content = it.content + delta) }
                            followConversationBottom(target)
                        }
                    },
                )
                target.isAwaitingFirstToken = false
                target.updateMessage(assistantId) {
                    if (it.content.isBlank() && answer.isNotBlank()) it.copy(content = answer) else it
                }
                val assistantMessage = target.messages.first { it.id == assistantId }
                runCatching {
                    historyRepository.appendMessage(
                        conversationId = target.id,
                        title = target.title,
                        messageId = assistantMessage.id,
                        role = assistantMessage.role.name,
                        content = assistantMessage.toStoredContent(),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                target.persistOrRemovePartialMessage(assistantId, historyRepository)
                throw cancelled
            } catch (error: Throwable) {
                target.persistOrRemovePartialMessage(assistantId, historyRepository)
                val safeDetail = error.message
                    ?.replace(activeConfiguration.apiKey, "••••")
                val errorMessage = ChatMessage(
                    nextMessageId(target),
                    MessageRole.Assistant,
                    if (error is ChatServiceUnavailable) unavailableMessage ?: connectionFailedMessage
                    else safeDetail ?: connectionFailedMessage,
                    isError = true,
                )
                target.messages += errorMessage
                followConversationBottom(target)
                runCatching {
                    historyRepository.appendMessage(
                        conversationId = target.id,
                        title = target.title,
                        messageId = errorMessage.id,
                        role = errorMessage.role.name,
                        content = errorMessage.content,
                        isError = true,
                    )
                }
            } finally {
                target.isGenerating = false
                target.isAwaitingFirstToken = false
                target.runningJob = null
            }
        }
    }

    fun regenerate(answer: ChatMessage) {
        val activeConfiguration = currentConfiguration ?: return
        val target = currentConversation ?: return
        if (target.isGenerating) return
        val answerIndex = target.messages.indexOfFirst { it.id == answer.id }
        if (answerIndex < 0 || answer.role != MessageRole.Assistant) return
        val promptIndex = (answerIndex - 1 downTo 0)
            .firstOrNull { target.messages[it].role == MessageRole.User }
            ?: return
        val prompt = target.messages[promptIndex].content
        val history = target.messages.take(promptIndex)
        streamScrollFollower.followLatest = listState.isAtConversationBottom(reverseLayout = compact)
        target.messages.subList(answerIndex, target.messages.size).clear()
        target.isGenerating = true
        target.isAwaitingFirstToken = true
        val replacementId = nextMessageId(target)
        target.messages += ChatMessage(replacementId, MessageRole.Assistant, "")
        followConversationBottom(target)
        target.runningJob = scope.launch {
            runCatching { historyRepository.deleteMessagesFrom(target.id, answer.id) }
            try {
                val reply = service.replyStreaming(
                    configuration = activeConfiguration,
                    history = history,
                    prompt = prompt,
                    onToolUse = { event ->
                        target.isAwaitingFirstToken = false
                        target.applyToolUseEvent(replacementId, event)
                        followConversationBottom(target)
                    },
                    onDelta = { delta ->
                        if (delta.isNotEmpty()) {
                            target.isAwaitingFirstToken = false
                            target.updateMessage(replacementId) { it.copy(content = it.content + delta) }
                            followConversationBottom(target)
                        }
                    },
                )
                target.isAwaitingFirstToken = false
                target.updateMessage(replacementId) {
                    if (it.content.isBlank() && reply.isNotBlank()) it.copy(content = reply) else it
                }
                val replacement = target.messages.first { it.id == replacementId }
                runCatching {
                    historyRepository.appendMessage(
                        conversationId = target.id,
                        title = target.title,
                        messageId = replacement.id,
                        role = replacement.role.name,
                        content = replacement.toStoredContent(),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                target.persistOrRemovePartialMessage(replacementId, historyRepository)
                throw cancelled
            } catch (error: Throwable) {
                target.persistOrRemovePartialMessage(replacementId, historyRepository)
                val safeDetail = error.message?.replace(activeConfiguration.apiKey, "••••")
                val replacement = ChatMessage(
                    nextMessageId(target),
                    MessageRole.Assistant,
                    if (error is ChatServiceUnavailable) unavailableMessage ?: connectionFailedMessage
                    else safeDetail ?: connectionFailedMessage,
                    isError = true,
                )
                target.messages += replacement
                followConversationBottom(target)
                runCatching {
                    historyRepository.appendMessage(
                        conversationId = target.id,
                        title = target.title,
                        messageId = replacement.id,
                        role = replacement.role.name,
                        content = replacement.content,
                        isError = true,
                    )
                }
            } finally {
                target.isGenerating = false
                target.isAwaitingFirstToken = false
                target.runningJob = null
            }
        }
    }

    fun exportConversation(action: ExportAction, selectedIds: Set<Long>? = null) {
        val target = currentConversation ?: return
        val messagesToExport = messagesForExport(target.messages, selectedIds)
        if (exporting || messagesToExport.isEmpty()) return
        val activeSecret = currentConfiguration?.apiKey.orEmpty()
        exporting = true
        exportNotice = exportingMessage
        scope.launch {
            val outcome = runCatching {
                val rendered = renderConversationImage(
                    textMeasurer = exportTextMeasurer,
                    graphicsLayer = exportLayer,
                    layoutDirection = layoutDirection,
                    title = target.title,
                    messages = messagesToExport.map {
                        ConversationExportMessage(
                            isUser = it.role == MessageRole.User,
                            content = redactExportSecrets(it.content, activeSecret),
                            isError = it.isError,
                        )
                    },
                    truncatedLabel = truncatedFooter,
                )
                val fileName = "kcode-${target.id}.png"
                rendered to when (action) {
                    ExportAction.Save -> imageSaver.save(rendered.image, fileName)
                    ExportAction.Share -> imageSaver.share(rendered.image, fileName)
                }
            }
            exportNotice = outcome.fold(
                onSuccess = { (rendered, saved) ->
                    when (saved) {
                        is ImageSaveResult.Saved -> resolveText(
                            currentLanguage,
                            if (rendered.truncated) UiText.ExportSavedTruncated else UiText.ExportSaved,
                            saved.location,
                        )
                        ImageSaveResult.Shared -> resolveText(currentLanguage, UiText.ShareOpened)
                        is ImageSaveResult.Failed -> resolveText(currentLanguage, UiText.ExportFailed, saved.reason ?: unknownError)
                        ImageSaveResult.Unsupported -> unsupportedMessage
                    }
                },
                onFailure = { resolveText(currentLanguage, UiText.ExportFailed, it.message ?: unknownError) },
            )
            exporting = false
            delay(4_000)
            exportNotice = null
        }
    }

    fun beginMessageSelection(message: ChatMessage) {
        focusManager.clearFocus(force = true)
        messageSelectionMode = true
        if (message.id !in selectedMessageIds) selectedMessageIds += message.id
    }

    fun toggleMessageSelection(message: ChatMessage) {
        if (message.id in selectedMessageIds) selectedMessageIds.remove(message.id)
        else selectedMessageIds += message.id
    }

    fun cancelMessageSelection() {
        messageSelectionMode = false
        selectedMessageIds.clear()
    }

    fun exportSelectedMessages(action: ExportAction) {
        if (selectedMessageIds.isEmpty()) return
        val selection = selectedMessageIds.toSet()
        cancelMessageSelection()
        exportConversation(action, selection)
    }

    LaunchedEffect(conversation?.id) {
        val count = conversation?.messages?.size ?: 0
        if (count > 0) {
            streamScrollFollower.followLatest = true
            val lastIndex = count - 1 + if (conversation?.isGenerating == true) 1 else 0
            listState.scrollToConversationBottom(reverseLayout = compact, targetIndex = lastIndex)
        }
    }

    val prepareForKeyboard: () -> Unit = {
        // Capture this before the viewport changes. Reverse layout preserves the bottom anchor
        // without any application-side work; only an off-bottom list needs a settle job.
        val startedAtBottom = listState.isAtConversationBottom(reverseLayout = compact)
        keyboardSettleJob?.cancel()
        keyboardSettleJob = if (compact && !startedAtBottom) scope.launch {
                var previousBottom = -1
                var stableFrames = 0
                var keyboardObserved = false

                // Eight unchanged frames keep the transition responsive while avoiding a
                // premature scroll during brief pauses in the platform keyboard animation.
                repeat(60) {
                    withFrameNanos { }
                    val currentBottom = imeInsets.getBottom(density)
                    if (currentBottom > 0) keyboardObserved = true
                    stableFrames = if (keyboardObserved && currentBottom == previousBottom) {
                        stableFrames + 1
                    } else {
                        0
                    }
                    previousBottom = currentBottom
                    if (stableFrames >= 8) {
                        val target = currentConversation ?: return@launch
                        if (target.messages.isNotEmpty()) {
                            val lastIndex = target.messages.lastIndex + if (target.isGenerating) 1 else 0
                            listState.animateToConversationBottom(reverseLayout = true, targetIndex = lastIndex)
                        }
                        return@launch
                    }
                }

                // Some hardware-keyboard and floating-keyboard modes expose no IME inset.
                // Fall back after a bounded wait instead of leaving the conversation midway.
                val target = currentConversation ?: return@launch
                if (target.messages.isNotEmpty()) {
                    val lastIndex = target.messages.lastIndex + if (target.isGenerating) 1 else 0
                    listState.animateToConversationBottom(reverseLayout = true, targetIndex = lastIndex)
                }
        } else {
            null
        }
        Unit
    }

    if (compact) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().background(Paper)) {
            val extraCompact = maxWidth < 360.dp
            val horizontalContentPadding = if (extraCompact) 12.dp else 20.dp
            val composerOuterPadding = if (extraCompact) 8.dp else 12.dp
            val listTopPadding = if (extraCompact) 84.dp else 92.dp
            val composerBottomPadding = if (mobileComposerHeightPx == 0) {
                176.dp
            } else {
                with(density) { mobileComposerHeightPx.toDp() } + 18.dp
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
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = centeredContent.pointerInput(Unit) {
                        detectTapGestures { focusManager.clearFocus(force = true) }
                    },
                    contentPadding = PaddingValues(
                        start = horizontalContentPadding,
                        end = horizontalContentPadding,
                        top = listTopPadding,
                        bottom = composerBottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (extraCompact) KcodeSpacing.lg else KcodeSpacing.xl),
                ) {
                    if (conversation.isGenerating && conversation.isAwaitingFirstToken) {
                        item(key = "thinking") { ThinkingRow(compact = true) }
                    }
                    items(conversation.messages.asReversed(), key = { it.id }) { message ->
                        MessageItem(
                            message = message,
                            compact = true,
                            canRegenerate = !messageSelectionMode && configuration != null && !conversation.isGenerating,
                            canShare = !messageSelectionMode && !conversation.isGenerating,
                            selectionMode = messageSelectionMode,
                            selected = message.id in selectedMessageIds,
                            regenerateDescription = regenerateDescription,
                            shareDescription = shareDescription,
                            onToggleSelection = { toggleMessageSelection(message) },
                            onShare = { beginMessageSelection(message) },
                            onRegenerate = {
                                focusManager.clearFocus(force = true)
                                regenerate(message)
                            },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !listState.isAtConversationBottom(reverseLayout = true),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(
                        bottom = composerOverlayBottom - KcodeSize.floatingShadowGutter,
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
                                    listState.animateToConversationBottom(reverseLayout = true, targetIndex = lastIndex)
                                }
                            },
                            size = 46.dp,
                        ) { Text("↓", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Light) }
                    }
                }
                MobileComposer(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .onSizeChanged { mobileComposerHeightPx = it.height }
                        .padding(horizontal = composerOuterPadding, vertical = 10.dp),
                    configuration = configuration,
                    generating = conversation.isGenerating,
                    replying = true,
                    focusRequester = focusRequester,
                    onFocus = prepareForKeyboard,
                    onModelClick = onSettings,
                    onConfigurationChange = onConfigurationChange,
                    onSend = ::send,
                    onStop = { conversation.runningJob?.cancel() },
                    toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                    toolPermissionMode = toolPermissionMode,
                    onToolPermissionModeChange = onToolPermissionModeChange,
                )
                exportNotice?.let { notice ->
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
                selectionMode = messageSelectionMode,
                selectedCount = selectedMessageIds.size,
                onCancelSelection = ::cancelMessageSelection,
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
        }
    } else {
        Column(modifier.fillMaxHeight().background(Paper)) {
            Row(
                Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (messageSelectionMode) {
                        FloatingCircleButton(
                            description = text(UiText.Cancel),
                            onClick = ::cancelMessageSelection,
                            size = 42.dp,
                        ) { Text("×", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Light) }
                        Text(
                            text(UiText.SelectedMessages, selectedMessageIds.size),
                            Modifier.padding(start = 12.dp).weight(1f),
                            color = Ink,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Box {
                            FloatingCircleButton(
                                description = shareDescription,
                                onClick = { desktopSelectionExportExpanded = true },
                                size = 42.dp,
                            ) { ExportMark() }
                            ExportOptionsBubble(
                                expanded = desktopSelectionExportExpanded,
                                placement = BubblePlacement.Below,
                                onDismissRequest = { desktopSelectionExportExpanded = false },
                                onSave = {
                                    desktopSelectionExportExpanded = false
                                    exportSelectedMessages(ExportAction.Save)
                                },
                                onShare = {
                                    desktopSelectionExportExpanded = false
                                    exportSelectedMessages(ExportAction.Share)
                                },
                            )
                        }
                        return@Row
                    }
                    QuietButton("☰", text(UiText.OpenSidebar)) {
                        focusManager.clearFocus(force = true)
                        onMenu()
                    }
                Text(
                    conversation?.title ?: text(UiText.NewChat),
                    Modifier.padding(start = 13.dp).weight(1f),
                    color = SoftInk,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box {
                        QuietButton("⇧", text(UiText.ExportConversation)) {
                            focusManager.clearFocus(force = true)
                            if (!conversation?.messages.isNullOrEmpty()) desktopExportExpanded = true
                        }
                        ExportOptionsBubble(
                            expanded = desktopExportExpanded,
                            placement = BubblePlacement.Below,
                            onDismissRequest = { desktopExportExpanded = false },
                            onSave = {
                                desktopExportExpanded = false
                                exportConversation(ExportAction.Save)
                            },
                            onShare = {
                                desktopExportExpanded = false
                                exportConversation(ExportAction.Share)
                            },
                        )
                    }
                    ModelBadge(
                        configuration = configuration,
                        onMissingConfiguration = {
                            focusManager.clearFocus(force = true)
                            onSettings()
                        },
                        onConfigurationChange = onConfigurationChange,
                    )
            }
            HorizontalDivider(color = Hairline, thickness = 0.5.dp)
            if (conversation == null || conversation.messages.isEmpty()) {
                Welcome(
                    modifier = Modifier.weight(1f).then(conversationContentMotion),
                    compact = false,
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().then(conversationContentMotion).pointerInput(Unit) {
                        detectTapGestures { focusManager.clearFocus(force = true) }
                    },
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    items(conversation.messages, key = { it.id }) { message ->
                        MessageItem(
                            message = message,
                            canRegenerate = !messageSelectionMode && configuration != null && !conversation.isGenerating,
                            canShare = !messageSelectionMode && !conversation.isGenerating,
                            selectionMode = messageSelectionMode,
                            selected = message.id in selectedMessageIds,
                            regenerateDescription = regenerateDescription,
                            shareDescription = shareDescription,
                            onToggleSelection = { toggleMessageSelection(message) },
                            onShare = { beginMessageSelection(message) },
                            onRegenerate = {
                                focusManager.clearFocus(force = true)
                                regenerate(message)
                            },
                        )
                    }
                    if (conversation.isGenerating && conversation.isAwaitingFirstToken) {
                        item(key = "thinking") { ThinkingRow() }
                    }
                }
                Composer(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    generating = conversation.isGenerating,
                    focusRequester = focusRequester,
                    onFocus = prepareForKeyboard,
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

private enum class ExportAction { Save, Share }

@Composable
private fun ExportOptionsBubble(
    expanded: Boolean,
    placement: BubblePlacement,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    KcodeBubblePopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        placement = placement,
        minWidth = 216.dp,
        maxWidth = 256.dp,
        maxHeight = 320.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = KcodeSpacing.sm)) {
            PopupNavigationRow(label = text(UiText.ExportConversation))
            HorizontalDivider(Modifier.padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair), color = Hairline, thickness = .7.dp)
            PopupSectionLabel(text(UiText.ExportAction))
            PopupChoiceRow(
                title = text(UiText.SaveToPhotos),
                showChevron = true,
                onClick = onSave,
            )
            PopupChoiceRow(
                title = text(UiText.ShareImage),
                showChevron = true,
                onClick = onShare,
            )
        }
    }
}

internal fun redactExportSecrets(content: String, activeSecret: String): String {
    val currentKeyRedacted = if (activeSecret.isBlank()) content else content.replace(activeSecret, "••••")
    return Regex("sk[-_][A-Za-z0-9_-]{6,}", RegexOption.IGNORE_CASE).replace(currentKeyRedacted, "••••")
}

internal fun messagesForExport(messages: List<ChatMessage>, selectedIds: Set<Long>?): List<ChatMessage> =
    if (selectedIds == null) messages.toList() else messages.filter { it.id in selectedIds }

internal fun sentenceSelectionRange(text: String, requestedOffset: Int): TextRange {
    if (text.isEmpty()) return TextRange.Zero
    fun isSentenceTerminator(index: Int): Boolean = when (text[index]) {
        '。', '！', '？', '!', '?', '；', ';', '\n' -> true
        // A dot inside a package name, domain, version, or decimal is not a sentence boundary.
        '.' -> {
            val previous = text.getOrNull(index - 1)
            val next = text.getOrNull(index + 1)
            previous?.isLetterOrDigit() != true || next?.isLetterOrDigit() != true
        }
        else -> false
    }
    var probe = requestedOffset.coerceIn(0, text.lastIndex)
    while (probe < text.lastIndex && text[probe].isWhitespace()) probe++

    var start = probe
    while (start > 0 && !isSentenceTerminator(start - 1)) start--
    while (start < text.length && text[start].isWhitespace()) start++

    var end = probe
    while (end < text.length && !isSentenceTerminator(end)) end++
    if (end < text.length && text[end] != '\n') end++
    while (end > start && text[end - 1].isWhitespace()) end--

    return if (start < end) TextRange(start, end) else TextRange(probe, (probe + 1).coerceAtMost(text.length))
}

@Composable
private fun CompactChatHeader(
    modifier: Modifier = Modifier,
    selectionMode: Boolean,
    selectedCount: Int,
    onCancelSelection: () -> Unit,
    onSaveSelection: () -> Unit,
    onShareSelection: () -> Unit,
    onMenu: () -> Unit,
    onNew: () -> Unit,
    exportEnabled: Boolean,
    onExportSave: () -> Unit,
    onExportShare: () -> Unit,
) {
    var exportExpanded by remember { mutableStateOf(false) }
    var selectionExportExpanded by remember { mutableStateOf(false) }
    val newChatDescription = text(UiText.NewChat)
    val exportDescription = text(UiText.ExportConversation)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    val extraCompact = maxWidth < 360.dp
    Row(
        Modifier.fillMaxWidth()
            .height(if (extraCompact) 72.dp else 80.dp)
            .padding(horizontal = if (extraCompact) 12.dp else 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            FloatingCircleButton(
                description = text(UiText.Cancel),
                onClick = onCancelSelection,
                size = if (extraCompact) 48.dp else 52.dp,
            ) { Text("×", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Light) }
            Box {
                Surface(
                    modifier = Modifier.height(if (extraCompact) 48.dp else 52.dp),
                    shape = RoundedCornerShape(26.dp),
                    color = Color.White.copy(alpha = .96f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Hairline.copy(alpha = .58f)),
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.pressClickable(
                            enabled = selectedCount > 0,
                            style = PressScaleStyle.Button,
                            onClick = { selectionExportExpanded = true },
                        ).padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text(UiText.SelectedMessages, selectedCount),
                            color = if (selectedCount > 0) Ink else SoftInk,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        ExportMark()
                    }
                }
                ExportOptionsBubble(
                    expanded = selectionExportExpanded,
                    placement = BubblePlacement.Below,
                    onDismissRequest = { selectionExportExpanded = false },
                    onSave = {
                        selectionExportExpanded = false
                        onSaveSelection()
                    },
                    onShare = {
                        selectionExportExpanded = false
                        onShareSelection()
                    },
                )
            }
            return@Row
        }
        FloatingCircleButton(
            description = text(UiText.OpenSidebar),
            onClick = onMenu,
            size = if (extraCompact) 48.dp else 52.dp,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = size.minDimension * .09f
                listOf(.25f, .5f, .75f).forEach { y ->
                    drawLine(
                        color = Ink,
                        start = Offset(size.width * .16f, size.height * y),
                        end = Offset(size.width * .84f, size.height * y),
                        strokeWidth = stroke,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.height(if (extraCompact) 50.dp else 54.dp),
            shape = RoundedCornerShape(if (extraCompact) 25.dp else 27.dp),
            color = Color.White.copy(alpha = .94f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Hairline.copy(alpha = .58f)),
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(if (extraCompact) 40.dp else 44.dp)
                        .pressClickable(style = PressScaleStyle.Button, onClick = onNew)
                        .clip(CircleShape).background(Ink)
                        .semantics { contentDescription = newChatDescription; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) { Text("+", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light) }
                Box {
                    Box(
                        Modifier.size(width = if (extraCompact) 44.dp else 48.dp, height = if (extraCompact) 40.dp else 44.dp)
                            .pressClickable(enabled = exportEnabled, style = PressScaleStyle.Button) { exportExpanded = true }
                            .clip(CircleShape)
                            .semantics { contentDescription = exportDescription; role = Role.Button },
                        contentAlignment = Alignment.Center,
                    ) { ExportMark() }
                    ExportOptionsBubble(
                        expanded = exportExpanded,
                        placement = BubblePlacement.Below,
                        onDismissRequest = { exportExpanded = false },
                        onSave = {
                            exportExpanded = false
                            onExportSave()
                        },
                        onShare = {
                            exportExpanded = false
                            onExportShare()
                        },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun ExportMark() {
    Canvas(Modifier.size(21.dp)) {
        val stroke = size.minDimension * .09f
        drawLine(Ink, Offset(size.width * .5f, size.height * .08f), Offset(size.width * .5f, size.height * .66f), stroke)
        drawLine(Ink, Offset(size.width * .27f, size.height * .31f), Offset(size.width * .5f, size.height * .08f), stroke)
        drawLine(Ink, Offset(size.width * .73f, size.height * .31f), Offset(size.width * .5f, size.height * .08f), stroke)
        drawLine(Ink, Offset(size.width * .16f, size.height * .6f), Offset(size.width * .16f, size.height * .9f), stroke)
        drawLine(Ink, Offset(size.width * .84f, size.height * .6f), Offset(size.width * .84f, size.height * .9f), stroke)
        drawLine(Ink, Offset(size.width * .16f, size.height * .9f), Offset(size.width * .84f, size.height * .9f), stroke)
    }
}

@Composable
private fun FloatingCircleButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(interaction, PressScaleStyle.Button).size(size)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        interactionSource = interaction,
        shape = CircleShape,
        color = Color.White,
        shadowElevation = 7.dp,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

private fun nextMessageId(conversation: ConversationState): Long =
    (conversation.messages.maxOfOrNull { it.id } ?: 0L) + 1L

private inline fun ConversationState.updateMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
    val index = messages.indexOfFirst { it.id == id }
    if (index >= 0) messages[index] = transform(messages[index])
}

private fun ConversationState.applyToolUseEvent(messageId: Long, event: ToolUseEvent) {
    updateMessage(messageId) { message ->
        when (event) {
            is ToolUseEvent.Started -> {
                val toolUse = ToolUseInfo(
                    id = event.id,
                    name = event.name,
                    input = event.input,
                    textOffset = message.content.length,
                )
                message.copy(toolUses = message.toolUses.filterNot { it.id == event.id } + toolUse)
            }
            is ToolUseEvent.Finished -> message.copy(
                toolUses = message.toolUses.map { toolUse ->
                    if (toolUse.id != event.id) toolUse else toolUse.copy(
                        output = event.output,
                        status = if (event.isError) ToolUseStatus.Failed else ToolUseStatus.Succeeded,
                    )
                },
            )
        }
    }
}

private suspend fun ConversationState.persistOrRemovePartialMessage(
    messageId: Long,
    historyRepository: ConversationHistoryRepository,
) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
    val partial = messages.firstOrNull { it.id == messageId }
    if (partial == null || (partial.content.isBlank() && partial.toolUses.isEmpty())) {
        messages.removeAll { it.id == messageId }
        return@withContext
    }
    runCatching {
        historyRepository.appendMessage(
            conversationId = id,
            title = title,
            messageId = partial.id,
            role = partial.role.name,
            content = partial.toStoredContent(),
            isError = partial.isError,
        )
    }
}

@Composable
private fun Welcome(
    modifier: Modifier,
    compact: Boolean,
    configuration: ModelConfiguration?,
    setupMessage: String?,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onSend: (String) -> Unit,
    onModelClick: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
    toolPermissionControlsAvailable: Boolean,
    toolPermissionMode: ToolPermissionMode,
    onToolPermissionModeChange: (ToolPermissionMode) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    if (compact) {
        Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
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
                    )
                    if (configuration == null || setupMessage != null) {
                        Text(
                            setupMessage ?: text(UiText.WelcomeBody),
                            Modifier.padding(top = KcodeSpacing.sm, start = KcodeSpacing.lg, end = KcodeSpacing.lg),
                            color = SoftInk,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            MobileComposer(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                configuration = configuration,
                generating = false,
                replying = false,
                focusRequester = focusRequester,
                onFocus = onFocus,
                onModelClick = onModelClick,
                onConfigurationChange = onConfigurationChange,
                onSend = onSend,
                onStop = {},
                toolPermissionControlsAvailable = toolPermissionControlsAvailable,
                toolPermissionMode = toolPermissionMode,
                onToolPermissionModeChange = onToolPermissionModeChange,
            )
        }
        return
    }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KcodeMark(size = 58.dp)
        Text(
            text(UiText.WelcomeTitle),
            Modifier.padding(top = KcodeSpacing.lg),
            color = Ink,
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            setupMessage ?: text(UiText.WelcomeBody),
            Modifier.padding(top = KcodeSpacing.xs, bottom = KcodeSpacing.xl),
            color = SoftInk,
            style = MaterialTheme.typography.bodyMedium,
        )
        Composer(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(.78f),
            generating = false,
            focusRequester = focusRequester,
            onFocus = onFocus,
            onSend = onSend,
            onStop = {},
            toolPermissionControlsAvailable = toolPermissionControlsAvailable,
            toolPermissionMode = toolPermissionMode,
            onToolPermissionModeChange = onToolPermissionModeChange,
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

@Composable
private fun MessageItem(
    message: ChatMessage,
    compact: Boolean = false,
    canRegenerate: Boolean,
    canShare: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    regenerateDescription: String,
    shareDescription: String,
    onToggleSelection: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val selectionShape = RoundedCornerShape(KcodeRadius.card)
    val clipboard = LocalClipboardManager.current
    val selectableText = remember(message.id, message.content) { markdownToPlainText(message.content) }
    var textSelection by remember(message.id) { mutableStateOf<TextFieldValue?>(null) }
    var intendedSentenceSelection by remember(message.id) { mutableStateOf<TextRange?>(null) }
    val selectionFocusRequester = remember(message.id) { FocusRequester() }
    val activateTextSelection: (String, Int) -> Unit = { blockText, blockOffset ->
        if (!selectionMode && selectableText.isNotEmpty()) {
            val blockRange = sentenceSelectionRange(blockText, blockOffset)
            val sentence = blockText.substring(blockRange.start, blockRange.end)
            val sentenceStart = selectableText.indexOf(sentence).takeIf { it >= 0 }
                ?: blockText.takeIf { it == selectableText }?.let { blockRange.start }
                ?: 0
            val range = if (sentence.isNotBlank() && sentenceStart >= 0) {
                TextRange(sentenceStart, (sentenceStart + sentence.length).coerceAtMost(selectableText.length))
            } else {
                sentenceSelectionRange(selectableText, sentenceStart)
            }
            intendedSentenceSelection = range
            textSelection = TextFieldValue(selectableText, range)
        }
    }
    LaunchedEffect(textSelection != null) {
        if (textSelection != null) {
            // Android may briefly replace a programmatic range with its word-level long-press
            // selection while focus settles. Reapply the sentence once, then yield control to
            // the user's draggable handles.
            delay(120)
            intendedSentenceSelection?.let { range ->
                textSelection = TextFieldValue(selectableText, range)
                intendedSentenceSelection = null
            }
        }
    }
    Box(
        Modifier.fillMaxWidth()
            .clip(selectionShape)
            .background(if (selected) Leaf.copy(alpha = .1f) else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                    SelectionIndicator(selected = selected)
                }
            }
            Box(
                Modifier.weight(1f),
                contentAlignment = if (message.role == MessageRole.User) Alignment.CenterEnd else Alignment.Center,
            ) {
                if (textSelection != null) {
                    MessageSelectionEditor(
                        value = textSelection!!,
                        onValueChange = { textSelection = it.copy(text = selectableText) },
                        focusRequester = selectionFocusRequester,
                        compact = compact,
                        isUser = message.role == MessageRole.User,
                        isError = message.isError,
                    )
                } else if (message.role == MessageRole.User) {
                    Box(
                        Modifier.widthIn(max = if (compact) 560.dp else 620.dp)
                            .fillMaxWidth(if (compact) .86f else .68f)
                            .clip(RoundedCornerShape(KcodeRadius.card))
                            .background(Panel)
                            .padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.sm),
                    ) {
                        LongPressMessageText(
                            content = message.content.trimEnd(),
                            color = Ink,
                            style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            onLongPressText = activateTextSelection,
                        )
                    }
                } else {
                    Column(
                        Modifier.widthIn(max = if (compact) 680.dp else 760.dp)
                            .fillMaxWidth(if (compact) 1f else .78f),
                    ) {
                        if (message.content.isNotEmpty() || message.toolUses.isNotEmpty()) {
                            AssistantMessageTimeline(
                                message = message,
                                compact = compact,
                                onLongPressText = activateTextSelection,
                            )
                            if (canRegenerate || canShare) {
                                AssistantActions(
                                    canRegenerate = canRegenerate,
                                    canShare = canShare,
                                    regenerateDescription = regenerateDescription,
                                    shareDescription = shareDescription,
                                    onRegenerate = onRegenerate,
                                    onShare = onShare,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (selectionMode) {
            Box(
                Modifier.matchParentSize()
                    .pressClickable(style = PressScaleStyle.Panel, onClick = onToggleSelection)
                    .semantics { role = Role.Checkbox },
            )
        }
        KcodeBubblePopup(
            expanded = textSelection != null,
            onDismissRequest = { textSelection = null },
            placement = BubblePlacement.Above,
            minWidth = 104.dp,
            maxWidth = 116.dp,
            maxHeight = 68.dp,
            focusable = false,
        ) {
            CompactCopyAction(
                label = text(UiText.CopyText),
                onClick = {
                    val value = textSelection ?: return@CompactCopyAction
                    val start = minOf(value.selection.start, value.selection.end)
                    val end = maxOf(value.selection.start, value.selection.end)
                    if (start < end) clipboard.setText(AnnotatedString(value.text.substring(start, end)))
                    textSelection = null
                },
            )
        }
    }
}

@Composable
private fun MessageSelectionEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    compact: Boolean,
    isUser: Boolean,
    isError: Boolean,
) {
    val shape = RoundedCornerShape(KcodeRadius.card)
    var textLayout by remember(value.text) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(focusRequester) {
        // This effect belongs to the field itself, so cancellation follows its attachment.
        // Waiting one frame avoids requesting focus before the FocusRequester node is mounted.
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    Box(
        Modifier.widthIn(max = if (isUser) 560.dp else 680.dp)
            .fillMaxWidth(if (isUser) .86f else 1f)
            .background(if (isUser) Panel else Color.Transparent, shape)
            .padding(horizontal = if (isUser) KcodeSpacing.md else 0.dp, vertical = KcodeSpacing.sm),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                readOnly = true,
                textStyle = (if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium).copy(
                    color = if (isError) Error else Ink,
                ),
                cursorBrush = SolidColor(LeafInk),
                onTextLayout = { textLayout = it },
            )
            textLayout?.let { layout ->
                val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
                val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
                AdjustableSelectionHandle(
                    cursor = layout.getCursorRect(start).let { Offset(it.left, it.bottom) },
                    layout = layout,
                    startHandle = true,
                    onOffsetChange = { offset ->
                        onValueChange(value.copy(selection = TextRange(offset.coerceAtMost(end), end)))
                    },
                )
                AdjustableSelectionHandle(
                    cursor = layout.getCursorRect(end).let { Offset(it.left, it.bottom) },
                    layout = layout,
                    startHandle = false,
                    onOffsetChange = { offset ->
                        onValueChange(value.copy(selection = TextRange(start, offset.coerceAtLeast(start))))
                    },
                )
            }
        }
    }
}

@Composable
private fun AdjustableSelectionHandle(
    cursor: Offset,
    layout: TextLayoutResult,
    startHandle: Boolean,
    onOffsetChange: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val touchSize = 34.dp
    val touchSizePx = with(density) { touchSize.toPx() }
    val stemInsetPx = with(density) { 2.dp.toPx() }
    val latestOffsetChange by rememberUpdatedState(onOffsetChange)
    Box(
        Modifier.offset {
            IntOffset(
                // Keep the visual and its full touch target inside the text bounds. The two
                // handles intentionally open toward the selected text, like native handles.
                x = (if (startHandle) cursor.x else cursor.x - touchSizePx).roundToInt(),
                y = (cursor.y - stemInsetPx).roundToInt(),
            )
        }.size(touchSize)
            .pointerInput(layout, startHandle) {
                var target = cursor
                detectDragGestures(
                    onDragStart = { target = cursor },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        target += dragAmount
                        val constrained = Offset(
                            x = target.x.coerceIn(0f, layout.size.width.toFloat()),
                            y = target.y.coerceIn(0f, layout.size.height.toFloat()),
                        )
                        latestOffsetChange(layout.getOffsetForPosition(constrained))
                    },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stemX = if (startHandle) 2.5.dp.toPx() else size.width - 2.5.dp.toPx()
            val bulbX = if (startHandle) 6.dp.toPx() else size.width - 6.dp.toPx()
            drawRoundRect(
                color = LeafInk,
                topLeft = Offset(stemX - 1.5.dp.toPx(), 0f),
                size = Size(3.dp.toPx(), 13.dp.toPx()),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
            drawCircle(color = LeafInk, radius = 5.5.dp.toPx(), center = Offset(bulbX, 15.dp.toPx()))
            drawCircle(color = Color.White, radius = 2.7.dp.toPx(), center = Offset(bulbX, 15.dp.toPx()))
        }
    }
}

@Composable
private fun CompactCopyAction(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().height(44.dp)
            .padding(4.dp)
            .clip(RoundedCornerShape(KcodeRadius.control))
            .hoverable(interaction)
            .pressScale(interaction, PressScaleStyle.Button)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LongPressMessageText(
    content: String,
    color: Color,
    style: androidx.compose.ui.text.TextStyle,
    onLongPressText: (String, Int) -> Unit,
) {
    var layout by remember(content) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        text = content,
        modifier = Modifier.onLongPressAfterRelease(content) { position ->
                onLongPressText(content, layout?.getOffsetForPosition(position) ?: 0)
        },
        color = color,
        style = style,
        onTextLayout = { layout = it },
    )
}

@Composable
private fun SelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(21.dp)
            .clip(CircleShape)
            .background(if (selected) LeafInk else Color.White)
            .border(1.5.dp, if (selected) LeafInk else SoftInk.copy(alpha = .7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AssistantMessageTimeline(
    message: ChatMessage,
    compact: Boolean,
    onLongPressText: (String, Int) -> Unit,
) {
    if (message.isError) {
        LongPressMessageText(
            content = message.content.trimEnd(),
            color = Error,
            style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            onLongPressText = onLongPressText,
        )
        return
    }

    var cursor = 0
    message.toolUses.sortedBy { it.textOffset }.forEach { toolUse ->
        val offset = toolUse.textOffset.coerceIn(cursor, message.content.length)
        AssistantMarkdownSegment(message.content.substring(cursor, offset), compact, onLongPressText)
        ToolUseRow(toolUse = toolUse, compact = compact)
        cursor = offset
    }
    AssistantMarkdownSegment(message.content.substring(cursor), compact, onLongPressText)
}

@Composable
private fun AssistantMarkdownSegment(
    content: String,
    compact: Boolean,
    onLongPressText: (String, Int) -> Unit,
) {
    if (content.isBlank()) return
    MarkdownText(
        markdown = content.trim(),
        compact = compact,
        color = Ink,
        onLongPressText = onLongPressText,
    )
}

@Composable
private fun ToolUseRow(toolUse: ToolUseInfo, compact: Boolean) {
    var expanded by remember(toolUse.id) { mutableStateOf(false) }
    val runningAlpha = if (toolUse.status == ToolUseStatus.Running) {
        val runningTransition = rememberInfiniteTransition(label = "tool-running-${toolUse.id}")
        val animatedAlpha by runningTransition.animateFloat(
            initialValue = .35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
            label = "tool-running-alpha",
        )
        animatedAlpha
    } else {
        1f
    }
    val statusText = when (toolUse.status) {
        ToolUseStatus.Running -> text(UiText.ToolRunning)
        ToolUseStatus.Succeeded -> text(UiText.ToolSucceeded)
        ToolUseStatus.Failed -> text(UiText.ToolFailed)
    }
    val statusColor = when (toolUse.status) {
        ToolUseStatus.Running -> LeafInk
        ToolUseStatus.Succeeded -> LeafInk
        ToolUseStatus.Failed -> Error
    }
    val summary = toolUseSummary(toolUse.input)

    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = KcodeSpacing.xs)
            .pressClickable { expanded = !expanded }
            .clip(RoundedCornerShape(KcodeRadius.control))
            .padding(horizontal = 2.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                when (toolUse.status) {
                    ToolUseStatus.Running -> Box(
                        Modifier.size(7.dp).alpha(runningAlpha).clip(CircleShape).background(Leaf),
                    )
                    ToolUseStatus.Succeeded -> Text("✓", color = LeafInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    ToolUseStatus.Failed -> Text("!", color = Error, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f).padding(start = KcodeSpacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        toolUseDisplayName(toolUse.name),
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "  $statusText",
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        color = SoftInk,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                    )
                }
            }
            Text(if (expanded) "⌃" else "⌄", color = SoftInk, fontSize = 15.sp)
        }

        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
            Column(
                Modifier.fillMaxWidth().padding(start = KcodeSpacing.lg, top = KcodeSpacing.xs, end = KcodeSpacing.hair)
                    .clip(RoundedCornerShape(KcodeRadius.control)).background(Panel)
                    .padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs),
            ) {
                ToolUseDetail(text(UiText.ToolInput), toolUse.input)
                if (toolUse.output.isNotBlank()) ToolUseDetail(text(UiText.ToolOutput), toolUse.output)
            }
        }
    }
}

@Composable
private fun ToolUseDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(KcodeSpacing.hair)) {
        Text(label.uppercase(), color = SoftInk, style = MaterialTheme.typography.labelSmall)
        Text(
            value.ifBlank { "—" },
            color = Ink,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 40,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun toolUseDisplayName(name: String): String = when (name.lowercase()) {
    "android_shell", "shell", "bash" -> "Shell"
    "read_file" -> "Read"
    "write_file" -> "Write"
    "edit_file" -> "Edit"
    "list_directory" -> "List"
    else -> name.split('_', '-').joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

private fun toolUseSummary(input: String): String {
    val compact = input.replace(Regex("\\s+"), " ").trim()
    if (compact.isEmpty() || compact == "{}") return ""
    val preferredValue = Regex(
        "\\\"(?:command|path|query|url|phoneNumber|message)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"",
    ).find(compact)?.groupValues?.getOrNull(1)
    return (preferredValue ?: compact)
        .replace("\\n", " ")
        .replace("\\\"", "\"")
        .let { if (it.length <= 180) it else it.take(179) + "…" }
}

@Composable
private fun AssistantActions(
    modifier: Modifier = Modifier,
    canRegenerate: Boolean,
    canShare: Boolean,
    regenerateDescription: String,
    shareDescription: String,
    onRegenerate: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier.height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canRegenerate) {
            Box(
                Modifier.size(30.dp)
                    .pressClickable(style = PressScaleStyle.Button, onClick = onRegenerate)
                    .clip(CircleShape)
                    .semantics { contentDescription = regenerateDescription; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Text("↻", color = SoftInk, fontSize = 21.sp, fontWeight = FontWeight.Light) }
        }
        if (canShare) {
            Box(
                Modifier.size(30.dp)
                    .pressClickable(style = PressScaleStyle.Button, onClick = onShare)
                    .clip(CircleShape)
                    .semantics { contentDescription = shareDescription; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { MessageShareMark() }
        }
    }
}

@Composable
private fun MessageShareMark() {
    Canvas(Modifier.size(17.dp)) {
        val stroke = size.minDimension * .1f
        drawLine(SoftInk, Offset(size.width * .5f, size.height * .08f), Offset(size.width * .5f, size.height * .65f), stroke, StrokeCap.Round)
        drawLine(SoftInk, Offset(size.width * .25f, size.height * .31f), Offset(size.width * .5f, size.height * .08f), stroke, StrokeCap.Round)
        drawLine(SoftInk, Offset(size.width * .75f, size.height * .31f), Offset(size.width * .5f, size.height * .08f), stroke, StrokeCap.Round)
        val path = Path().apply {
            moveTo(size.width * .18f, size.height * .52f)
            lineTo(size.width * .18f, size.height * .9f)
            lineTo(size.width * .82f, size.height * .9f)
            lineTo(size.width * .82f, size.height * .52f)
        }
        drawPath(path, SoftInk, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun ThinkingRow(compact: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "thinking-alpha",
    )
    Row(
        Modifier.widthIn(max = if (compact) 680.dp else 760.dp)
            .fillMaxWidth(if (compact) 1f else .78f)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Leaf))
        Text(text(UiText.Thinking), Modifier.padding(start = KcodeSpacing.xs), color = SoftInk, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MobileModelSelector(
    configuration: ModelConfiguration?,
    actionSize: Dp,
    extraCompact: Boolean,
    onMissingConfiguration: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.width(if (extraCompact) KcodeSize.narrowModelControl else KcodeSize.compactModelControl)
                .height(actionSize)
                .pressClickable(style = PressScaleStyle.Button) {
                    if (configuration == null) onMissingConfiguration() else expanded = true
                }
                .clip(RoundedCornerShape(KcodeRadius.control))
                .padding(horizontal = KcodeSpacing.hair),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                configuration?.let { modelOption(it.modelId)?.let { option -> modelName(option) } }
                    ?: text(UiText.ChooseModel),
                color = Ink,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Medium",
                modifier = Modifier.padding(top = KcodeSpacing.hair / 2),
                color = SoftInk,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        if (configuration != null) {
            ModelConfigurationBubble(
                expanded = expanded,
                configuration = configuration,
                placement = BubblePlacement.Above,
                onDismissRequest = { expanded = false },
                onChange = onConfigurationChange,
            )
        }
    }
}

@Composable
private fun MobileComposer(
    modifier: Modifier,
    configuration: ModelConfiguration?,
    generating: Boolean,
    replying: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onModelClick: () -> Unit,
    onConfigurationChange: (ModelConfiguration) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    toolPermissionControlsAvailable: Boolean,
    toolPermissionMode: ToolPermissionMode,
    onToolPermissionModeChange: (ToolPermissionMode) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val sendInteraction = remember { MutableInteractionSource() }

    fun submit() {
        if (value.isNotBlank() && !generating) {
            onSend(value)
            value = ""
        }
    }

    Surface(
        modifier = modifier.imePadding(),
        shape = RoundedCornerShape(KcodeRadius.panel),
        color = Color.White.copy(alpha = .96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Hairline.copy(alpha = .55f)),
        shadowElevation = 16.dp,
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints {
        val extraCompact = maxWidth < 340.dp
        val actionSize = KcodeSize.compactControl
        Column(
            Modifier.padding(
                start = if (extraCompact) KcodeSpacing.sm else KcodeSpacing.md,
                top = KcodeSpacing.sm,
                end = KcodeSpacing.sm,
                bottom = KcodeSpacing.xs,
            ),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 120.dp)
                    .onFocusChanged { if (it.isFocused) onFocus() }
                    .focusRequester(focusRequester),
                enabled = !generating,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Leaf),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text(if (replying) UiText.ReplyPlaceholder else UiText.MessagePlaceholder),
                                color = SoftInk.copy(alpha = .72f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(actionSize).clip(CircleShape).background(Mist),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Light)
                }
                if (toolPermissionControlsAvailable) {
                    Spacer(Modifier.width(KcodeSpacing.hair))
                    ToolPermissionModeButton(
                        mode = toolPermissionMode,
                        size = actionSize,
                        onModeChange = onToolPermissionModeChange,
                    )
                }
                Spacer(Modifier.weight(1f))
                MobileModelSelector(
                    configuration = configuration,
                    actionSize = actionSize,
                    extraCompact = extraCompact,
                    onMissingConfiguration = onModelClick,
                    onConfigurationChange = onConfigurationChange,
                )
                Spacer(Modifier.width(KcodeSpacing.hair))
                Button(
                    onClick = if (generating) onStop else ::submit,
                    enabled = generating || value.isNotBlank(),
                    modifier = Modifier.pressScale(
                        sendInteraction,
                        PressScaleStyle.Button,
                        generating || value.isNotBlank(),
                    ).size(actionSize),
                    interactionSource = sendInteraction,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Leaf,
                        disabledContainerColor = Mist,
                        disabledContentColor = SoftInk,
                    ),
                ) {
                    if (generating) {
                        StopGlyph(size = if (extraCompact) 12.5.dp else 14.dp)
                    } else {
                        Text("↑", fontSize = 18.sp)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun Composer(
    modifier: Modifier,
    generating: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    toolPermissionControlsAvailable: Boolean,
    toolPermissionMode: ToolPermissionMode,
    onToolPermissionModeChange: (ToolPermissionMode) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val sendInteraction = remember { MutableInteractionSource() }

    fun submit() {
        if (value.isNotBlank() && !generating) {
            onSend(value)
            value = ""
        }
    }

    Surface(
        modifier = modifier.imePadding(),
        shape = RoundedCornerShape(KcodeRadius.card),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Hairline),
        shadowElevation = 7.dp,
    ) {
        Column(Modifier.padding(start = KcodeSpacing.md, top = KcodeSpacing.sm, end = KcodeSpacing.sm, bottom = KcodeSpacing.xs)) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().height(62.dp)
                    .onFocusChanged { if (it.isFocused) onFocus() }
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                            submit(); true
                        } else false
                    },
                enabled = !generating,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                cursorBrush = SolidColor(Leaf),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(
                            text(UiText.MessagePlaceholder),
                            color = SoftInk.copy(alpha = .78f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(KcodeSize.compactControl).clip(CircleShape).background(Mist),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Light)
                }
                if (toolPermissionControlsAvailable) {
                    Spacer(Modifier.width(KcodeSpacing.hair))
                    ToolPermissionModeButton(
                        mode = toolPermissionMode,
                        size = KcodeSize.compactControl,
                        onModeChange = onToolPermissionModeChange,
                    )
                }
                Text(
                    text(UiText.SendShortcut),
                    Modifier.padding(start = KcodeSpacing.xs),
                    color = SoftInk.copy(alpha = .7f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = if (generating) onStop else ::submit,
                    enabled = generating || value.isNotBlank(),
                    modifier = Modifier.pressScale(
                        sendInteraction,
                        PressScaleStyle.Button,
                        generating || value.isNotBlank(),
                    ).size(KcodeSize.compactControl),
                    interactionSource = sendInteraction,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Leaf,
                        disabledContainerColor = Mist,
                        disabledContentColor = SoftInk,
                    ),
                ) {
                    if (generating) {
                        StopGlyph(size = 12.5.dp)
                    } else {
                        Text("↑", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolPermissionModeButton(
    mode: ToolPermissionMode,
    size: Dp,
    onModeChange: (ToolPermissionMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modeName = mode.code
    val description = "${text(UiText.ToolPermissionMode)}: $modeName"
    Box {
        Column(
            modifier = Modifier.width(KcodeSize.compactPermissionControl).height(size)
                .pressClickable(style = PressScaleStyle.Button) { expanded = true }
                .clip(RoundedCornerShape(KcodeRadius.control))
                .semantics { contentDescription = description; role = Role.Button }
                .padding(horizontal = KcodeSpacing.hair),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text(UiText.ToolPermissionShort),
                color = Ink,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                modeName,
                modifier = Modifier.padding(top = KcodeSpacing.hair / 2),
                color = SoftInk,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        KcodeBubblePopup(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            placement = BubblePlacement.Above,
            minWidth = 232.dp,
            maxWidth = 272.dp,
            maxHeight = 440.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = KcodeSpacing.sm)) {
                PopupNavigationRow(label = text(UiText.ToolPermissionMode), value = modeName)
                HorizontalDivider(Modifier.padding(horizontal = KcodeSpacing.md, vertical = KcodeSpacing.hair), color = Hairline, thickness = .7.dp)
                PopupSectionLabel(text(UiText.PermissionLevel))
                ToolPermissionMode.entries.forEach { item ->
                    val itemDescription = when (item) {
                        ToolPermissionMode.Deny -> text(UiText.ToolPermissionDenyDescription)
                        ToolPermissionMode.Ask -> text(UiText.ToolPermissionAskDescription)
                        ToolPermissionMode.Bypass -> text(UiText.ToolPermissionBypassDescription)
                    }
                    PopupChoiceRow(
                        title = item.code,
                        subtitle = itemDescription,
                        selected = item == mode,
                        titleColor = if (item == ToolPermissionMode.Bypass) Error else Ink,
                        onClick = {
                            expanded = false
                            onModeChange(item)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A platform-independent stop mark. Font square glyphs vary in size and baseline,
 * so this uses cubic Bézier corners for a consistent soft-square silhouette.
 */
@Composable
private fun StopGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val side = this.size.minDimension
        val inset = side * .035f
        val left = inset
        val top = inset
        val right = side - inset
        val bottom = side - inset
        val radius = (right - left) * .29f
        val control = radius * .5522848f

        val path = Path().apply {
            moveTo(left + radius, top)
            lineTo(right - radius, top)
            cubicTo(
                right - radius + control, top,
                right, top + radius - control,
                right, top + radius,
            )
            lineTo(right, bottom - radius)
            cubicTo(
                right, bottom - radius + control,
                right - radius + control, bottom,
                right - radius, bottom,
            )
            lineTo(left + radius, bottom)
            cubicTo(
                left + radius - control, bottom,
                left, bottom - radius + control,
                left, bottom - radius,
            )
            lineTo(left, top + radius)
            cubicTo(
                left, top + radius - control,
                left + radius - control, top,
                left + radius, top,
            )
            close()
        }
        drawPath(path, color = Ink.copy(alpha = .94f))
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

@Composable
private fun ActionRow(label: String, shortcut: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(KcodeRadius.control))
            .background(if (hovered) Mist else Color.Transparent)
            .border(1.dp, Hairline, RoundedCornerShape(KcodeRadius.control))
            .hoverable(interaction)
            .pressScale(interaction, PressScaleStyle.Panel)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = KcodeSpacing.sm, vertical = KcodeSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("＋", color = LeafInk, fontSize = 19.sp)
        Text(label, Modifier.padding(start = KcodeSpacing.xs).weight(1f), color = Ink, style = MaterialTheme.typography.labelLarge)
        Text(shortcut, color = SoftInk.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun QuietButton(symbol: String, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(KcodeSize.compactControl)
            .pressScale(interaction, PressScaleStyle.Button)
            .clip(CircleShape)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = SoftInk, fontSize = 17.sp) }
}

@Composable
private fun KcodeMark(size: Dp) {
    Image(
        painter = painterResource(Res.drawable.kcode_mark),
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun BoxWithResponsiveWidth(content: @Composable (Dp) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) { content(maxWidth) }
}

private fun LazyListState.isAtConversationBottom(reverseLayout: Boolean): Boolean =
    if (reverseLayout) {
        firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= 1
    } else {
        !canScrollForward
    }

private suspend fun LazyListState.animateToConversationBottom(reverseLayout: Boolean, targetIndex: Int) {
    if (reverseLayout) {
        animateScrollToItem(0)
    } else {
        animateToBottom(targetIndex)
    }
}

private suspend fun LazyListState.scrollToConversationBottom(reverseLayout: Boolean, targetIndex: Int) {
    if (targetIndex < 0) return
    if (reverseLayout) {
        scrollToItem(0)
    } else {
        scrollToItem(targetIndex)
        val layout = layoutInfo
        val target = layout.visibleItemsInfo.lastOrNull { it.index == targetIndex } ?: return
        val remainingDistance = (
            target.offset + target.size + layout.afterContentPadding - layout.viewportEndOffset
        ).coerceAtLeast(0)
        if (remainingDistance > 0) scrollBy(remainingDistance.toFloat() + 1f)
    }
}

private suspend fun LazyListState.animateToBottom(targetIndex: Int) {
    if (targetIndex < 0) return
    animateScrollToItem(targetIndex)

    val layout = layoutInfo
    val target = layout.visibleItemsInfo.lastOrNull { it.index == targetIndex } ?: return
    val remainingDistance = (
        target.offset + target.size + layout.afterContentPadding - layout.viewportEndOffset
    ).coerceAtLeast(0)
    if (remainingDistance > 0) {
        // One extra pixel avoids leaving canScrollForward=true because of rounding.
        animateScrollBy(remainingDistance.toFloat() + 1f)
    }
}
