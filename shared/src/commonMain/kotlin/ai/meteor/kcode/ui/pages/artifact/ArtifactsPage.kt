package ai.meteor.kcode.ui.pages.artifact

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactLauncher
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.localization.UiText
import ai.meteor.kcode.localization.text
import ai.meteor.kcode.ui.component.KcodeIcon
import ai.meteor.kcode.ui.component.KcodeIconAsset
import ai.meteor.kcode.ui.design.Hairline
import ai.meteor.kcode.ui.design.Ink
import ai.meteor.kcode.ui.design.KcodeRadius
import ai.meteor.kcode.ui.design.KcodeSpacing
import ai.meteor.kcode.ui.design.Paper
import ai.meteor.kcode.ui.design.SoftInk
import ai.meteor.kcode.webcontainer.WebContainerController
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var revision by remember { mutableIntStateOf(0) }
    var artifacts by remember { mutableStateOf<List<Artifact>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository, revision) {
        artifacts = null
        failure = null
        runCatching { repository.list() }
            .onSuccess { artifacts = it }
            .onFailure { failure = it.message ?: "Unknown error" }
    }

    Column(
        modifier.fillMaxSize().background(Paper).padding(horizontal = KcodeSpacing.lg, vertical = KcodeSpacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (compact) {
                    OutlinedButton(onClick = onMenu, contentPadding = ButtonDefaults.ContentPadding) {
                        KcodeIcon(KcodeIconAsset.Menu, Ink, Modifier.size(22.dp))
                    }
                }
                Column(Modifier.padding(start = if (compact) KcodeSpacing.sm else 0.dp)) {
                    Text(text(UiText.ArtifactLibrary), color = Ink, style = MaterialTheme.typography.headlineMedium)
                    artifacts?.let {
                        Text(text(UiText.ArtifactCount, it.size), color = SoftInk, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            OutlinedButton(onClick = { revision++ }) {
                Text(text(UiText.RefreshArtifacts))
            }
        }

        when {
            artifacts == null && failure == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Ink)
            }
            failure != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text(UiText.ArtifactLoadFailed, failure.orEmpty()), color = MaterialTheme.colorScheme.error)
            }
            artifacts.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text(UiText.EmptyArtifacts), color = SoftInk, style = MaterialTheme.typography.bodyLarge)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(top = KcodeSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(KcodeSpacing.sm),
            ) {
                items(artifacts.orEmpty(), key = Artifact::id) { artifact ->
                    ArtifactRow(
                        artifact = artifact,
                        enabled = webContainerController != null,
                        onOpen = {
                            val controller = webContainerController ?: return@ArtifactRow
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
private fun ArtifactRow(
    artifact: Artifact,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(KcodeRadius.panel),
        border = BorderStroke(1.dp, Hairline),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(KcodeSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                KcodeIcon(KcodeIconAsset.WebContainer, Ink, Modifier.size(28.dp))
                Column(Modifier.padding(start = KcodeSpacing.sm)) {
                    Text(artifact.name, color = Ink, style = MaterialTheme.typography.titleMedium)
                    Text(
                        artifact.description.ifBlank { text(UiText.WebAppArtifact) },
                        color = SoftInk,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onOpen,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
            ) {
                Text(text(UiText.OpenArtifact))
            }
        }
    }
}

private fun textFailure(message: String?): String = message?.take(1_024) ?: "Unknown error"
