package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.StoredDynamicPlugin
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.packages.PluginPackageArchive

/** Uses the ordinary native verifier and cache, independently of the running product tree. */
class ProfilePluginPackageImporter(
    private val resolver: PluginPackageResolver,
    private val cached: (String) -> PluginPackageImport,
) : ProfilePluginImportPreparation {
    override suspend fun prepare(archive: ProfileArchiveReference, existing: ProfileLock): PreparedProfilePluginImport {
        existing.validate()
        val manifest = withContext(Dispatchers.IO) {
            interruptibleBundleIo { PluginPackageArchive().inspect(File(archive.archivePath), archive.sha256).manifest }
        }
        val previous = if (existing.packages.isEmpty()) emptyList() else
            resolver.resolve(existing.packages.map { cached(it.archiveSha256) }, emptyList())
        require(profileLock(PluginCompositionSnapshot(external = previous.map(StoredDynamicPlugin::from))).packages.associateBy { it.id } == existing.packages.associateBy { it.id }) {
            "Existing plugin packages differ from the Profile lock"
        }
        val resolved = resolver.resolve(listOf(PluginPackageImport(archive.archivePath, archive.sha256)), previous)
        require(resolved.size == 1 && resolved.single().id == manifest.id) { "Plugin resolver returned an unexpected identity" }
        return PreparedProfilePluginImport(manifest.id,
            profileLock(PluginCompositionSnapshot(external = (previous.filterNot { it.id == manifest.id } + resolved).map(StoredDynamicPlugin::from))))
    }
}
