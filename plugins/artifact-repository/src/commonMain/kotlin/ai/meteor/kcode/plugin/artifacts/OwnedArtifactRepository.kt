package ai.meteor.kcode.plugin.artifacts

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifact.SaveWebArtifactRequest
import ai.meteor.kcode.plugin.api.PluginOperationOwner

internal open class OwnedArtifactRepository(
    private val delegate: ArtifactRepository,
    protected val owner: PluginOperationOwner,
) : ArtifactRepository {
    override suspend fun list(): List<Artifact> = owner.run { delegate.list() }
}

internal class OwnedMutableArtifactRepository(
    private val delegate: MutableArtifactRepository,
    owner: PluginOperationOwner,
) : OwnedArtifactRepository(delegate, owner), MutableArtifactRepository {
    override suspend fun saveWebApp(request: SaveWebArtifactRequest): Artifact = owner.run { delegate.saveWebApp(request) }
}
