package ai.meteor.kcode.ui.pages.artifact

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactLauncher
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.ui.component.FloatingCircleButton
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.component.PressScaleStyle
import ai.meteor.kcode.ui.component.pressClickable
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.Leaf
import ai.meteor.kcode.ui.design.PaleMint
import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.webcontainer.WebContainerController
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ArtifactsPage(
    repository: ArtifactRepository,
    webContainerController: WebContainerController?,
    compact: Boolean,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var artifacts by remember { mutableStateOf<List<Artifact>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        artifacts = null
        failure = null
        runCatching { repository.list() }
            .onSuccess { artifacts = it }
            .onFailure { failure = it.message ?: "Unknown error" }
    }

    Column(modifier.fillMaxSize().background(Paper)) {
        ArtifactTopBar(
            compact = compact,
            onMenu = onMenu,
        )

        when {
            artifacts == null && failure == null -> ArtifactPageCenter {
                CircularProgressIndicator(color = Ink)
            }
            failure != null -> ArtifactPageCenter {
                Text(
                    text(UiText.ArtifactLoadFailed, failure.orEmpty()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            artifacts.isNullOrEmpty() -> ArtifactPageCenter {
                EmptyArtifactDesktop()
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (compact) 96.dp else 112.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = KcodeSpacing.md,
                    top = KcodeSpacing.lg,
                    end = KcodeSpacing.md,
                    bottom = KcodeSpacing.xxl,
                ),
                horizontalArrangement = Arrangement.spacedBy(KcodeSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(KcodeSpacing.lg),
            ) {
                items(artifacts.orEmpty(), key = Artifact::id) { artifact ->
                    ArtifactAppIcon(
                        artifact = artifact,
                        enabled = webContainerController != null,
                        onOpen = {
                            val controller = webContainerController ?: return@ArtifactAppIcon
                            scope.launch {
                                runCatching { ArtifactLauncher(controller).open(artifact) }
                                    .onFailure { failure = textFailure(it.message) }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactTopBar(
    compact: Boolean,
    onMenu: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .height(if (compact) 76.dp else 84.dp)
            .padding(horizontal = if (compact) KcodeSpacing.md else KcodeSpacing.lg, vertical = KcodeSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact) {
            FloatingCircleButton(
                description = text(UiText.OpenSidebar),
                onClick = onMenu,
                size = 52.dp,
                border = BorderStroke(1.dp, Hairline.copy(alpha = .58f)),
            ) {
                KcodeIcon(KcodeIconAsset.Menu, Ink, Modifier.size(22.dp))
            }
        } else {
            Box(Modifier.size(52.dp))
        }

        Surface(
            shape = RoundedCornerShape(27.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, Hairline.copy(alpha = .58f)),
            shadowElevation = 7.dp,
        ) {
            Box(
                Modifier.height(52.dp).padding(horizontal = KcodeSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text(UiText.ArtifactLibrary),
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Box(Modifier.size(52.dp))
    }
}

@Composable
private fun ArtifactAppIcon(
    artifact: Artifact,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val launchDescription = "${text(UiText.OpenArtifact)}: ${artifact.name}"
    Column(
        Modifier.fillMaxWidth()
            .alpha(if (enabled) 1f else .48f)
            .pressClickable(enabled = enabled, style = PressScaleStyle.Button, onClick = onOpen)
            .semantics {
                contentDescription = launchDescription
                role = Role.Button
            }
            .padding(vertical = KcodeSpacing.hair),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(KcodeRadius.card),
            color = Color.Transparent,
            border = BorderStroke(1.dp, Leaf.copy(alpha = .36f)),
            shadowElevation = 6.dp,
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(PaleMint, MaterialTheme.colorScheme.surface, Leaf.copy(alpha = .34f))),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(KcodeRadius.control),
                    color = Paper.copy(alpha = .82f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        KcodeIcon(KcodeIconAsset.WebContainer, Ink, Modifier.size(27.dp))
                    }
                }
            }
        }
        Text(
            artifact.name,
            modifier = Modifier.padding(top = KcodeSpacing.xs).width(104.dp),
            color = Ink,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtifactPageCenter(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(horizontal = KcodeSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun EmptyArtifactDesktop() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(KcodeRadius.card),
            color = PaleMint,
        ) {
            Box(contentAlignment = Alignment.Center) {
                KcodeIcon(KcodeIconAsset.Artifacts, SoftInk, Modifier.size(30.dp))
            }
        }
        Text(
            text(UiText.EmptyArtifacts),
            modifier = Modifier.padding(top = KcodeSpacing.md),
            color = SoftInk,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

private fun textFailure(message: String?): String = message?.take(1_024) ?: "Unknown error"
