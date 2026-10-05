package ai.meteor.kcode.plugin

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifact.SaveWebArtifactRequest
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

/** Binds a caller-owned repository without selecting codecs, storage or paths. */
internal object HostArtifactInputPlugin : Plugin<ArtifactRepository> {
    override val name = "kcode-host-artifact-input"

    override suspend fun apply(ctx: Context, config: ArtifactRepository, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        effect.collect(Disposable { owner.close() })
        val repository = if (config is MutableArtifactRepository) {
            object : MutableArtifactRepository {
                override suspend fun list(): List<Artifact> = owner.run { config.list() }
                override suspend fun saveWebApp(request: SaveWebArtifactRequest): Artifact =
                    owner.run { config.saveWebApp(request) }
            }
        } else {
            object : ArtifactRepository {
                override suspend fun list(): List<Artifact> = owner.run { config.list() }
            }
        }
        KcodeArtifacts(ctx, repository)
    }
}
