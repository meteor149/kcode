package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import java.io.File
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.cordis.packages.PackageHost
import org.cordis.packages.PluginPackageArchive

const val ProfileBundleArchiveRuntime = "kcode-profile-bundle"
const val ProfileBundleArchiveExtension = "ai.meteor.kcode.profile-bundle"

@Serializable
data class ProfileBundleArchiveMetadata(
    val formatVersion: Int = 1,
    val packages: List<ProfileBundleArchiveCode>,
)

@Serializable
data class ProfileBundleArchiveCode(val id: String, val path: String)

data class ProfileBundleArchiveInput(val archive: File, val sha256: String)

/** Pure-data Bundle distribution; embedded native releases are verified but never mounted. */
class ProfileBundleArchive(
    private val directory: File,
    private val host: PackageHost,
    private val resolver: PluginPackageResolver,
) {
    suspend fun prepare(archive: File, sha256: String, id: String, displayName: String = id): PortableProfileDocument =
        prepare(listOf(ProfileBundleArchiveInput(archive, sha256)), id, displayName)

    suspend fun prepare(inputs: List<ProfileBundleArchiveInput>, id: String, displayName: String = id): PortableProfileDocument {
        ProfileDefinition(id = id, displayName = displayName).validate()
        require(inputs.isNotEmpty()) { "Select at least one Bundle archive" }
        val loaded = inputs.map { load(it) }
        val bundles = loaded.map { it.first }
        val releases = linkedMapOf<String, PluginPackageImport>()
        val dependencies = linkedMapOf<String, String>()
        for ((_, code, versions) in loaded) {
            code.forEach { (packageId, release) ->
                require(
                    releases[packageId]?.let { it.sha256 == release.sha256 } != false &&
                        dependencies[packageId]?.let { it == versions.getValue(packageId) } != false,
                ) { "Bundle code release conflict: '$packageId'" }
                releases.putIfAbsent(packageId, release)
                dependencies[packageId] = versions.getValue(packageId)
            }
        }
        val definition = ProfileDefinition(
            id = id,
            displayName = displayName,
            bundles = bundles.map { ProfileBundleReference(it.id, it.version) },
            dataScope = ProfileDataScope(workspace = "profile"),
        )
        // Validate data before staging code. The package resolver enforces actual SDK/platform/dependency identity.
        ProfileCompiler().compile(definition, bundles).requireValid()
        val packages = resolver.resolve(releases.values.toList(), emptyList())
        require(packages.associate { it.id to it.version } == dependencies && packages.all {
            it.packageInstallation?.archiveSha256 == releases[it.id]?.sha256
        }) { "Embedded Bundle releases differ from their declarations" }
        val resolved = ProfileResolver(resolver).resolve(definition, bundles, emptyMap(), emptySet(), previous = packages)
        currentCoroutineContext().ensureActive()
        return PortableProfileDocument(definition = definition, bundles = bundles, lock = resolved.lock).also { it.validate() }
    }

    private suspend fun load(input: ProfileBundleArchiveInput): Triple<ProfileBundle, Map<String, PluginPackageImport>, Map<String, String>> =
        withContext(Dispatchers.IO) {
            interruptibleBundleIo {
                val deployed = PluginPackageArchive().deploy(input.archive, input.sha256, File(directory, "bundles"))
                val manifest = deployed.manifest
                require(manifest.extensions.keys == setOf(ProfileBundleArchiveExtension)) { "Invalid Bundle archive extensions" }
                require(manifest.variants.size == 1) { "Bundle archives have one pure-data variant" }
                val variant = manifest.select(host.copy(runtimes = host.runtimes + (ProfileBundleArchiveRuntime to "1")))
                require(variant.runtime.id == ProfileBundleArchiveRuntime && variant.runtime.entryPoint == "bundle.json" &&
                    variant.runtime.minVersion == "1" && variant.runtime.metadata.isEmpty() && variant.artifact == "bundle.json" &&
                    variant.extensions.isEmpty()) { "Invalid Bundle data variant" }
                val metadata = Json.decodeFromJsonElement(ProfileBundleArchiveMetadata.serializer(),
                    manifest.extensions.getValue(ProfileBundleArchiveExtension))
                require(metadata.formatVersion == 1 && metadata.packages.map { it.id }.distinct().size == metadata.packages.size) {
                    "Invalid Bundle code metadata"
                }
                val files = manifest.files.associateBy { it.path }
                require(files.keys == metadata.packages.map { it.path }.toSet() + "bundle.json") { "Undeclared Bundle payload" }
                require(metadata.packages.map { it.id }.toSet() == manifest.dependencies.map { it.id }.toSet()) { "Bundle dependencies differ from embedded code" }
                val bundleFile = deployed.artifact(variant)
                val bytes = bundleFile.inputStream().use { it.readNBytes(2 * 1024 * 1024 + 1) }
                require(bytes.size <= 2 * 1024 * 1024) { "Bundle definition is too large" }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val bundle = Json.decodeFromString(ProfileBundle.serializer(), text)
                require(bundle.id == manifest.id && bundle.version == manifest.version && bundle.formatVersion == 1) { "Bundle identity differs from archive" }
                val releases = metadata.packages.associate { item ->
                    val record = requireNotNull(files[item.path]) { "Missing Bundle code" }
                    require(item.path == "code/${record.sha256}.bin") { "Invalid Bundle code address" }
                    item.id to PluginPackageImport(File(deployed.directory, "payload/${item.path}").absolutePath, record.sha256)
                }
                Triple(bundle, releases, manifest.dependencies.associate { it.id to it.version })
            }.also { ensureActive() }
        }
}

private suspend fun <T> interruptibleBundleIo(block: () -> T): T = try {
    runInterruptible(block = block)
} catch (failure: InterruptedIOException) {
    currentCoroutineContext().ensureActive()
    throw failure
}
