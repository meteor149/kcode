package ai.meteor.kcode.plugin.artifacts

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactFileStore
import ai.meteor.kcode.artifact.ArtifactRepository
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifact.MutableArtifactFileStore
import ai.meteor.kcode.plugin.api.ArtifactFileStoreFactory
import ai.meteor.kcode.plugin.api.PluginCleanupException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ai.meteor.kcode.plugin.api.KcodeArtifacts
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import org.cordis.ConfigValidator
import org.cordis.Context
import org.cordis.Disposable
import org.cordis.EffectScope
import org.cordis.Plugin

object ArtifactsProviderPlugin : Plugin<ArtifactRepository> {
    override val name = "kcode-artifacts-platform"
    override suspend fun apply(ctx: Context, config: ArtifactRepository, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        effect.collect(Disposable { owner.close() })
        val repository = if (config is MutableArtifactRepository) {
            OwnedMutableArtifactRepository(config, owner)
        } else {
            OwnedArtifactRepository(config, owner)
        }
        KcodeArtifacts(ctx, repository)
    }
}

/** The product repository is allocated by the owning plugin, not the platform host. */
object FileArtifactsProviderPlugin : Plugin<ArtifactFileStore> {
    override val name = "kcode-artifacts-files"
    override suspend fun apply(ctx: Context, config: ArtifactFileStore, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        effect.collect(Disposable { owner.close() })
        val delegate = FileArtifactRepository(config)
        val repository = if (config is MutableArtifactFileStore) {
            OwnedMutableArtifactRepository(delegate, owner)
        } else {
            OwnedArtifactRepository(delegate, owner)
        }
        KcodeArtifacts(ctx, repository)
    }
}

object EmptyArtifactsProviderPlugin : Plugin<Unit> {
    override val config = ConfigValidator<Unit> { it }
    override val name = "kcode-artifacts-empty"
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        ArtifactsProviderPlugin.apply(ctx, object : ArtifactRepository {
            override suspend fun list() = emptyList<Artifact>()
        }, effect)
    }
}

object FactoryFileArtifactsProviderPlugin : Plugin<ArtifactFileStoreFactory> {
    override val name = "kcode-artifacts-owned-files"
    override suspend fun apply(ctx: Context, config: ArtifactFileStoreFactory, effect: EffectScope) {
        val owner = PluginOperationOwner(name)
        val resource = withContext(NonCancellable) {
            owner.run { config.create() }.also { resource ->
                effect.collect {
                    owner.requireCanClose()
                    withContext(NonCancellable) {
                        val failures = mutableListOf<Throwable>()
                        runCatching { owner.close() }.exceptionOrNull()?.let(failures::add)
                        runCatching { resource.close() }.exceptionOrNull()?.let(failures::add)
                        if (failures.isNotEmpty()) throw PluginCleanupException(name, failures)
                    }
                }
            }
        }
        if (!effect.isActive) return
        val delegate = FileArtifactRepository(resource.store)
        val repository = if (resource.store is MutableArtifactFileStore) {
            OwnedMutableArtifactRepository(delegate, owner)
        } else OwnedArtifactRepository(delegate, owner)
        KcodeArtifacts(ctx, repository)
    }
}
