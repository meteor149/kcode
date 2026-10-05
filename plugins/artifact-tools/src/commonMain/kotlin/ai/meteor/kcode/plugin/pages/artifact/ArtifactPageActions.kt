package ai.meteor.kcode.plugin.pages.artifact

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.webcontainer.WebContainerController

/** Owns page operations while borrowing storage and the optional web controller. */
internal class ArtifactPageActions {
    private val owner = PluginOperationOwner("Artifact page")

    suspend fun list(repository: ArtifactRepository): List<Artifact> = owner.run { repository.list() }

    suspend fun open(controller: WebContainerController, artifact: Artifact) =
        owner.run { ArtifactLauncher(controller).open(artifact) }

    suspend fun close() = owner.close()
}
