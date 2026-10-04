package ai.meteor.kcode.plugin.pages.artifact

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactType

import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebPreviewResult
import ai.meteor.kcode.webcontainer.WebPreviewRequest

internal class ArtifactLauncher(
    private val controller: WebContainerController,
) {
    suspend fun open(artifact: Artifact): WebPreviewResult = when (artifact.type) {
        ArtifactType.WebApp -> controller.launch(
            WebPreviewRequest(
                entryPath = artifact.entryPath,
                title = artifact.name,
            ),
        )
    }
}
