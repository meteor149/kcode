package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.cordis.packages.PackageDependency
import org.cordis.packages.PackageArchiveLimits
import org.cordis.packages.PackageFile
import org.cordis.packages.PackageRuntime
import org.cordis.packages.PackageTarget
import org.cordis.packages.PackageVariant
import org.cordis.packages.PluginPackageArchive
import org.cordis.packages.PluginPackageManifest
import org.cordis.packages.packageFileSha256

/** Publisher-owned data/code inputs; host compatibility and configuration validation remain import checks. */
class ProfileBundleArchiveWriter {
    suspend fun pack(
        bundle: ProfileBundle,
        code: List<ProfileBundleArchiveInput>,
        targets: List<PackageTarget>,
        output: File,
    ): String {
        // Freeze caller collections before the first suspension. Layers need not compile in isolation.
        val bytes = Json.encodeToString(ProfileBundle.serializer(), bundle).toByteArray(Charsets.UTF_8)
        require(bundle.formatVersion == 1 && bytes.size <= ProfileBundleDefinitionByteLimit) { "Invalid/oversized Bundle definition" }
        val frozenTargets = Json.decodeFromString(ListSerializer(PackageTarget.serializer()),
            Json.encodeToString(ListSerializer(PackageTarget.serializer()), targets))
        val inputs = code.map { it.copy() }
        val limits = PackageArchiveLimits()
        require(inputs.size + 2 <= limits.maxEntries) { "Too many embedded code archives" }
        val outputPath = output.toPath().toAbsolutePath().normalize()
        require(inputs.none { it.archive.toPath().toAbsolutePath().normalize() == outputPath }) { "Output would replace a code input" }
        return withContext(Dispatchers.IO) {
            val operation = currentCoroutineContext()
            val payload = Files.createTempDirectory("kcode-bundle-pack-").toFile()
            try {
                interruptibleBundleIo {
                    val archives = PluginPackageArchive(limits)
                    val definition = File(payload, "bundle.json").apply { writeBytes(bytes) }
                    var totalBytes = bytes.size.toLong()
                    val releases = inputs.map { input ->
                        operation.ensureActive()
                        require(input.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid code archive digest" }
                        require(Files.isRegularFile(input.archive.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Invalid code archive" }
                        val path = "code/${input.sha256}.bin"
                        val snapshot = File(payload, path)
                        snapshot.parentFile.mkdirs()
                        require(!snapshot.exists()) { "Duplicate code archive" }
                        input.archive.inputStream().use { source ->
                            snapshot.outputStream().use { destination ->
                                val buffer = ByteArray(64 * 1024)
                                var size = 0L
                                while (true) {
                                    operation.ensureActive()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    size += count
                                    totalBytes += count
                                    require(size <= limits.maxEntryBytes && totalBytes <= limits.maxExpandedBytes) { "Embedded code archives are too large" }
                                    destination.write(buffer, 0, count)
                                }
                            }
                        }
                        archives.inspect(snapshot, input.sha256).manifest to PackageFile(path, snapshot.length(), input.sha256)
                    }.sortedBy { it.first.id }
                    require(releases.map { it.first.id }.distinct().size == releases.size) { "Conflicting code package identities" }
                    val manifest = PluginPackageManifest(
                        id = bundle.id,
                        version = bundle.version,
                        dependencies = releases.map { PackageDependency(it.first.id, it.first.version) },
                        variants = listOf(PackageVariant("data", frozenTargets,
                            PackageRuntime(ProfileBundleArchiveRuntime, "bundle.json", "1"), "bundle.json")),
                        files = listOf(PackageFile("bundle.json", definition.length(), packageFileSha256(definition))) + releases.map { it.second },
                        extensions = JsonObject(mapOf(ProfileBundleArchiveExtension to Json.encodeToJsonElement(
                            ProfileBundleArchiveMetadata.serializer(), ProfileBundleArchiveMetadata(packages = releases.map {
                                ProfileBundleArchiveCode(it.first.id, it.second.path)
                            }),
                        ))),
                    )
                    operation.ensureActive()
                    archives.pack(manifest, payload, output)
                }
            } finally {
                // The directory and every child were created by this operation, not supplied by the caller.
                Files.walk(payload.toPath()).use { paths ->
                    var failure: Exception? = null
                    paths.sorted(Comparator.reverseOrder()).forEach { path ->
                        try { Files.deleteIfExists(path) } catch (error: Exception) {
                            if (failure == null) failure = error else failure!!.addSuppressed(error)
                        }
                    }
                    failure?.let { throw it }
                }
            }
        }
    }
}

internal const val ProfileBundleDefinitionByteLimit = 2 * 1024 * 1024

internal fun readBundleArchiveJson(file: File): String {
    val bytes = file.inputStream().use { it.readNBytes(ProfileBundleDefinitionByteLimit + 1) }
    require(bytes.size <= ProfileBundleDefinitionByteLimit) { "Bundle definition is too large" }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
