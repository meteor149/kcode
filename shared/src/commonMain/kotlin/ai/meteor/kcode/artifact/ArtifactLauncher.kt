package ai.meteor.kcode.artifact

import ai.meteor.kcode.webcontainer.WebContainerController
import ai.meteor.kcode.webcontainer.WebPreviewResult
import ai.meteor.kcode.webcontainer.WebPreviewRequest

class ArtifactLauncher(
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
