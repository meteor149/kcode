package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.PluginPackageImport
import ai.meteor.kcode.plugin.PluginPackageResolver
import ai.meteor.kcode.plugin.api.PluginCompositionSnapshot
import ai.meteor.kcode.plugin.api.StoredDynamicPlugin
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.cordis.packages.PackageHost
import org.cordis.packages.PackageTarget
import org.cordis.packages.PluginPackageArchive

const val ProfileArchiveRuntime = "kcode-profile-archive"
const val ProfileArchiveExtension = "ai.meteor.kcode.profile-archive"

/** Full frozen intent plus exact code bytes; metadata publication/activation remain separate. */
class ProfileArchiveExchange(
    private val directory: File,
    private val host: PackageHost,
    private val resolver: PluginPackageResolver,
    private val builtinModules: suspend () -> Set<String> = { emptySet() },
) : ProfileArchiveTransport {
    override suspend fun prepareArchive(input: ProfileArchiveReference, id: String, displayName: String) =
        prepare(ProfileBundleArchiveInput(File(input.archivePath), input.sha256), id, displayName, builtinModules())

    override suspend fun exportArchive(
        management: ProfileManagement,
        request: ProfilePortableExport,
        consume: suspend (ProfileArchiveReference) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val temporary = Files.createTempDirectory("kcode-profile-lease-")
        val archive = temporary.resolve("export.kprofile")
        try {
            val digest = export(management, request.target, request.expectedRevision, archive.toFile())
            currentCoroutineContext().ensureActive()
            consume(ProfileArchiveReference(archive.toString(), digest))
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                try { Files.deleteIfExists(archive) } finally { Files.deleteIfExists(temporary) }
            }
        }
    }

    suspend fun export(
        management: ProfileManagement,
        target: ProfileTarget,
        expectedRevision: Long,
        output: File,
    ): String = withContext(Dispatchers.IO) {
        val temporary = Files.createTempDirectory("kcode-profile-export-")
        val staged = temporary.resolve("export.kprofile").toFile()
        try {
            val digest = management.preparePortableExport(target, expectedRevision) { text, source ->
                val document = ProfilePortableExporter.decode(text)
                require(document.lock == source.lock) { "Exported code differs from its generation" }
                val inputs = source.composition.external.map { stored ->
                    val spec = stored.toSpec()
                    resolver.verify(spec)
                    val lock = requireNotNull(spec.packageInstallation) { "Missing archive lock" }
                    ProfileBundleArchiveInput(File(lock.archivePath), lock.archiveSha256)
                }
                val destination = output.toPath().toAbsolutePath().normalize()
                require(inputs.none { input ->
                    val path = input.archive.toPath().toAbsolutePath().normalize()
                    path == destination || Files.exists(destination) && Files.isSameFile(path, destination)
                }) { "Profile export would replace locked code" }
                val bytes = text.toByteArray(Charsets.UTF_8)
                val identity = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                ProfileDataArchiveWriter().pack(bytes, "profile.$identity",
                    "1.0.0", "profile.json", ProfileArchiveRuntime, ProfileArchiveExtension, inputs,
                    listOf("windows", "linux", "macos", "android").map { PackageTarget(it, listOf("arm", "x86")) }, staged)
            }
            currentCoroutineContext().ensureActive()
            // The revision was checked after staging. Publish only complete, reviewed archive bytes.
            withContext(Dispatchers.IO) {
                val operation = currentCoroutineContext()
                interruptibleBundleIo {
                    val destination = output.toPath().toAbsolutePath().normalize()
                    Files.createDirectories(destination.parent)
                    // Copy into the destination filesystem before atomic replacement.
                    val sibling = Files.createTempFile(destination.parent, ".kcode-profile-export-", ".tmp")
                    try {
                        Files.copy(staged.toPath(), sibling, StandardCopyOption.REPLACE_EXISTING)
                        operation.ensureActive()
                        Files.move(sibling, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(sibling) }
                }
            }
            digest
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                try { Files.deleteIfExists(staged.toPath()) } finally { Files.deleteIfExists(temporary) }
            }
        }
    }

    suspend fun prepare(
        input: ProfileBundleArchiveInput,
        id: String,
        displayName: String = id,
        builtinModules: Set<String> = emptySet(),
    ): PortableProfileDocument {
        ProfileDefinition(id = id, displayName = displayName).validate()
        val builtins = builtinModules.toSet()
        val loaded = withContext(Dispatchers.IO) {
            interruptibleBundleIo {
                val deployed = PluginPackageArchive().deploy(input.archive, input.sha256, File(directory, "profile-archives"))
                val manifest = deployed.manifest
                require(manifest.extensions.keys == setOf(ProfileArchiveExtension) && manifest.variants.size == 1) { "Invalid Profile archive metadata" }
                val variant = manifest.select(host.copy(runtimes = host.runtimes + (ProfileArchiveRuntime to "1")))
                require(variant.artifact == "profile.json" && variant.runtime.entryPoint == "profile.json" &&
                    variant.runtime.id == ProfileArchiveRuntime && variant.runtime.minVersion == "1" &&
                    variant.runtime.metadata.isEmpty() && variant.extensions.isEmpty()) { "Invalid Profile data variant" }
                val document = ProfilePortableExporter.decode(readBundleArchiveJson(deployed.artifact(variant)))
                val definitionFile = requireNotNull(manifest.files.firstOrNull { it.path == "profile.json" })
                require(manifest.id == "profile.${definitionFile.sha256}" && manifest.version == "1.0.0") { "Profile archive identity mismatch" }
                val metadata = Json.decodeFromJsonElement(ProfileBundleArchiveMetadata.serializer(), manifest.extensions.getValue(ProfileArchiveExtension))
                require(metadata.formatVersion == 1 && metadata.packages.map { it.id }.distinct().size == metadata.packages.size) { "Invalid Profile code metadata" }
                val files = manifest.files.associateBy { it.path }
                val locked = document.lock.packages.associateBy { it.id }
                require(metadata.packages.map { it.id }.toSet() == locked.keys &&
                    manifest.dependencies.associate { it.id to it.version } == locked.mapValues { it.value.version } &&
                    files.keys == metadata.packages.map { it.path }.toSet() + "profile.json") { "Profile archive code differs from its lock" }
                val imports = metadata.packages.map { item ->
                    val release = locked.getValue(item.id)
                    val record = requireNotNull(files[item.path]) { "Missing Profile code" }
                    require(record.sha256 == release.archiveSha256 && item.path == "code/${record.sha256}.bin") { "Invalid Profile code address" }
                    PluginPackageImport(File(deployed.directory, "payload/${item.path}").absolutePath, record.sha256)
                }
                document to imports
            }
        }
        val (original, imports) = loaded
        val packages = resolver.resolve(imports, emptyList())
        require(packages.map { it.id }.distinct().size == packages.size && packages.size == original.lock.packages.size) { "Invalid resolved Profile code" }
        val expected = original.lock.packages.associateBy { it.id }
        packages.forEach { spec ->
            val lock = requireNotNull(expected[spec.id]) { "Undeclared Profile code" }
            val installation = requireNotNull(spec.packageInstallation)
            require(spec.version == lock.version && installation.archiveSha256 == lock.archiveSha256 &&
                installation.dependencies == lock.dependencies) { "Profile code identity differs from its portable lock" }
        }
        val nativeLock = profileLock(PluginCompositionSnapshot(external = packages.map(StoredDynamicPlugin::from)))
        val definition = original.definition.copy(id = id, displayName = displayName, dataScope = ProfileDataScope(workspace = "profile"))
        val resolved = ProfileResolver(resolver).resolve(definition, original.bundles, emptyMap(), builtins,
            previous = packages, expectedLock = nativeLock)
        currentCoroutineContext().ensureActive()
        return original.copy(definition = definition, lock = resolved.lock).also { it.validate() }
    }
}
