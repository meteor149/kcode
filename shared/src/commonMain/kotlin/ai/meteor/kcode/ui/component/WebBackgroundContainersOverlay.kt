package ai.meteor.kcode.ui.component

import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebContainerInfo
import ai.meteor.kcode.webcontainer.WebContainerState
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.ui.design.KcodeSize
import ai.meteor.kcode.ui.design.KcodeSpacing
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun WebBackgroundContainersOverlay(
    controller: WebContainerController,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    var containers by remember(controller) { mutableStateOf(emptyList<WebContainerInfo>()) }
    var expanded by remember(controller) { mutableStateOf(false) }
    var availableSize by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var contentCenter by remember { mutableStateOf<Offset?>(null) }
    var collapsedCenter by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    val edgeMarginPx = with(LocalDensity.current) { KcodeSpacing.sm.toPx() }

    fun clampedCenter(center: Offset, childSize: IntSize = contentSize): Offset {
        if (availableSize == IntSize.Zero || childSize == IntSize.Zero) return center

        fun clampAxis(value: Float, parent: Int, child: Int): Float {
            val minimum = edgeMarginPx + child / 2f
            val maximum = parent - edgeMarginPx - child / 2f
            return if (minimum <= maximum) value.coerceIn(minimum, maximum) else parent / 2f
        }

        return Offset(
            x = clampAxis(center.x, availableSize.width, childSize.width),
            y = clampAxis(center.y, availableSize.height, childSize.height),
        )
    }

    suspend fun refresh() {
        runCatching { controller.list() }
            .onSuccess { all ->
                containers = all.filter { it.state == WebContainerState.Background }
                if (containers.isEmpty()) expanded = false
            }
    }

    LaunchedEffect(controller) {
        while (currentCoroutineContext().isActive) {
            refresh()
            delay(400)
        }
    }

    LaunchedEffect(availableSize, contentSize, expanded) {
        if (availableSize == IntSize.Zero || contentSize == IntSize.Zero) return@LaunchedEffect
        val currentCenter = contentCenter ?: Offset(
                x = availableSize.width - edgeMarginPx - contentSize.width / 2f,
                y = availableSize.height / 2f,
            )
        if (expanded) {
            contentCenter = clampedCenter(currentCenter)
        } else {
            val restoredCenter = clampedCenter(collapsedCenter ?: currentCenter)
            contentCenter = restoredCenter
            if (collapsedCenter == null) collapsedCenter = restoredCenter
        }
    }

    if (containers.isEmpty()) return

    val dragModifier = Modifier.pointerInput(availableSize, contentSize, expanded) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            val draggedCenter = clampedCenter((contentCenter ?: Offset.Zero) + dragAmount)
            contentCenter = draggedCenter
            if (!expanded) collapsedCenter = draggedCenter
        }
    }
    val glassBackground = MaterialTheme.colorScheme.surface
    val glassTint = glassBackground.copy(alpha = 0.72f)
    fun glassModifier(shape: Shape) = Modifier
        .clip(shape)
        .hazeEffect(hazeState) {
            backgroundColor = glassBackground
            blurRadius = 14.dp
            tints = listOf(HazeTint(glassTint))
            noiseFactor = 0.025f
            blurredEdgeTreatment = BlurredEdgeTreatment(shape)
        }
    val border = BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
    )
    val center = contentCenter ?: Offset(
        x = availableSize.width - edgeMarginPx,
        y = availableSize.height / 2f,
    )

    Box(modifier = modifier.onSizeChanged { availableSize = it }) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (center.x - contentSize.width / 2f).roundToInt(),
                        y = (center.y - contentSize.height / 2f).roundToInt(),
                    )
                }
                .onSizeChanged { contentSize = it }
                .animateContentSize(),
        ) {
            if (expanded) {
                BackgroundContainerPanel(
                    containers = containers,
                    modifier = glassModifier(MaterialTheme.shapes.extraLarge),
                    dragModifier = dragModifier,
                    border = border,
                    onCollapse = { expanded = false },
                    onRestore = { container ->
                        scope.launch {
                            runCatching { controller.setState(container.id, WebContainerState.Foreground) }
                            refresh()
                        }
                    },
                    onClose = { container ->
                        scope.launch {
                            runCatching { controller.close(container.id) }
                            refresh()
                        }
                    },
                )
            } else {
                FloatingCircleButton(
                    description = text(UiText.WebExpandBackground),
                    onClick = {
                        collapsedCenter = contentCenter
                        expanded = true
                    },
                    modifier = glassModifier(CircleShape).then(dragModifier),
                    size = 48.dp,
                    width = 68.dp,
                    containerColor = Color.Transparent,
                    border = border,
                    shadowElevation = 0.dp,
                ) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        KcodeIcon(
                            asset = KcodeIconAsset.WebContainer,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp),
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).size(18.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = containers.size.coerceAtMost(99).toString(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundContainerPanel(
    containers: List<WebContainerInfo>,
    modifier: Modifier,
    dragModifier: Modifier,
    border: BorderStroke,
    onCollapse: () -> Unit,
    onRestore: (WebContainerInfo) -> Unit,
    onClose: (WebContainerInfo) -> Unit,
) {
    Surface(
        modifier = modifier.widthIn(min = 272.dp, max = 360.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(KcodeSpacing.sm)) {
            Row(
                modifier = dragModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(horizontal = KcodeSpacing.hair)) {
                    Text(
                        text = text(UiText.WebBackgroundContainers),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = text(UiText.WebBackgroundCount, containers.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCollapse, modifier = Modifier.size(KcodeSize.touchTarget)) {
                    KcodeIcon(
                        asset = KcodeIconAsset.ChevronDown,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                        contentDescription = text(UiText.WebCollapseBackground),
                    )
                }
            }
            Spacer(Modifier.size(KcodeSpacing.hair))
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(KcodeSpacing.xs),
            ) {
                containers.forEach { container ->
                    BackgroundContainerRow(container, onRestore, onClose)
                }
            }
        }
    }
}

@Composable
private fun BackgroundContainerRow(
    container: WebContainerInfo,
    onRestore: (WebContainerInfo) -> Unit,
    onClose: (WebContainerInfo) -> Unit,
) {
    val displayTitle = container.title.ifBlank { "Web" }
    val restoreDescription = text(UiText.WebRestoreContainer, displayTitle)
    Surface(
        onClick = { onRestore(container) },
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).semantics {
            contentDescription = restoreDescription
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.62f),
    ) {
        Row(
            modifier = Modifier.padding(start = KcodeSpacing.sm, top = KcodeSpacing.xs, bottom = KcodeSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KcodeIcon(
                asset = KcodeIconAsset.WebContainer,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = KcodeSpacing.sm)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = container.entryPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            IconButton(
                onClick = { onClose(container) },
                modifier = Modifier.size(KcodeSize.touchTarget),
            ) {
                KcodeIcon(
                    asset = KcodeIconAsset.Close,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                    contentDescription = text(UiText.WebCloseContainer, displayTitle),
                )
            }
        }
    }
}
