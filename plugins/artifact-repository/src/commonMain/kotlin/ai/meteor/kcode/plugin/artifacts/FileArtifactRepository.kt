package ai.meteor.kcode.plugin.artifacts

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactFileStore
import ai.meteor.kcode.artifact.ArtifactManifestPath
import ai.meteor.kcode.artifact.ArtifactResourcesRoot
import ai.meteor.kcode.artifact.ArtifactType
import ai.meteor.kcode.artifact.MutableArtifactFileStore
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifact.SaveWebArtifactRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import ai.meteor.kcode.plugin.api.PluginCleanupException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FileArtifactRepository(
    private val store: ArtifactFileStore,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : MutableArtifactRepository {
    private val mutationMutex = Mutex()

    override suspend fun list(): List<Artifact> {
        val manifest = loadManifest()
        require(manifest.version == CurrentManifestVersion) {
            "Unsupported artifact manifest version: ${manifest.version}"
        }
        require(manifest.artifacts.size <= MaxArtifacts) { "Artifact manifest contains too many entries" }
        val ids = mutableSetOf<String>()
        return manifest.artifacts.map { stored ->
            require(ids.add(stored.id)) { "Duplicate artifact id: ${stored.id}" }
            val artifact = stored.toArtifact()
            require(store.exists(artifact.entryPath)) { "Artifact entry does not exist: ${artifact.entryPath}" }
            artifact
        }
    }

    override suspend fun saveWebApp(request: SaveWebArtifactRequest): Artifact = mutationMutex.withLock {
        val mutableStore = requireNotNull(store as? MutableArtifactFileStore) {
            "This artifact store is read-only"
        }
        require(IdPattern.matches(request.id)) { "Invalid artifact id: ${request.id}" }
        require(request.id == request.id.lowercase()) { "Artifact id must be lowercase" }
        val sourceDirectory = normalizeWorkspaceDirectory(request.sourceDirectory)
        require(!sourceDirectory.startsWith("/workspace/artifacts/")) {
            "Artifact source must be outside the managed artifact directory"
        }
        val candidate = StoredArtifact(
            id = request.id,
            name = request.name,
            type = ArtifactType.WebApp.code,
            directory = request.id,
            entryPoint = request.entryPoint,
            description = request.description,
        )
        val artifact = candidate.toArtifact()
        val manifest = loadManifest()
        require(manifest.artifacts.none { it.id == artifact.id }) { "Artifact id already exists: ${artifact.id}" }
        require(manifest.artifacts.none { it.directory == artifact.directory }) {
            "Artifact directory already exists: ${artifact.directory}"
        }

        val staging = "$ArtifactResourcesRoot/.staging-${artifact.id}"
        val target = "$ArtifactResourcesRoot/${artifact.directory}"
        require(!mutableStore.exists(staging) && !mutableStore.exists(target)) { "Artifact target already exists" }
        var published = false
        try {
            copyDirectory(mutableStore, sourceDirectory, staging)
            require(mutableStore.exists("$staging/${artifact.entryPoint}")) {
                "Web app entry does not exist in source directory: ${artifact.entryPoint}"
            }
            mutableStore.moveTree(staging, target)
            val updated = manifest.copy(artifacts = manifest.artifacts + candidate)
            val encoded = json.encodeToString(updated).encodeToByteArray()
            require(encoded.size <= MaxManifestBytes) { "Artifact manifest is too large" }
            currentCoroutineContext().ensureActive()
            // Manifest publication is the commit edge. Cancellation after it must retain the resources.
            withContext(NonCancellable) {
                mutableStore.writeBytesAtomically(ArtifactManifestPath, encoded)
                published = true
            }
        } catch (error: Throwable) {
            if (!published) {
                val failures = withContext(NonCancellable) {
                    listOf(staging, target).mapNotNull { path ->
                        runCatching { mutableStore.deleteTree(path) }.exceptionOrNull()
                    }
                }
                if (failures.isNotEmpty()) throw PluginCleanupException("Artifact rollback", listOf(error) + failures)
            }
            throw error
        }
        artifact
    }

    private suspend fun loadManifest(): StoredArtifactManifest {
        val manifestText = store.readText(ArtifactManifestPath) ?: return StoredArtifactManifest()
        require(manifestText.encodeToByteArray().size <= MaxManifestBytes) { "Artifact manifest is too large" }
        return json.decodeFromString(manifestText)
    }

    private suspend fun copyDirectory(
        store: MutableArtifactFileStore,
        source: String,
        target: String,
    ) {
        val pending = ArrayDeque<Pair<String, String>>()
        pending += source to target
        var fileCount = 0
        var totalBytes = 0L
        while (pending.isNotEmpty()) {
            val (sourceDirectory, targetDirectory) = pending.removeFirst()
            store.list(sourceDirectory).forEach { entry ->
                val name = entry.path.substringAfterLast('/')
                require(name.isNotBlank() && name != "." && name != "..") { "Invalid source entry" }
                val destination = "$targetDirectory/$name"
                if (entry.directory) {
                    pending += entry.path to destination
                } else {
                    fileCount++
                    require(fileCount <= MaxArtifactFiles) { "Web artifact contains too many files" }
                    val bytes = requireNotNull(store.readBytes(entry.path)) { "Source file disappeared: ${entry.path}" }
                    totalBytes += bytes.size
                    require(totalBytes <= MaxArtifactBytes) { "Web artifact exceeds the size limit" }
                    store.writeBytesAtomically(destination, bytes)
                }
            }
        }
    }

    private fun StoredArtifact.toArtifact(): Artifact {
        require(IdPattern.matches(id)) { "Invalid artifact id: $id" }
        val normalizedName = name.trim().replace(Regex("\\s+"), " ")
        require(normalizedName.isNotEmpty() && normalizedName.length <= MaxNameChars) { "Invalid artifact name: $name" }
        val normalizedDirectory = normalizeRelativePath(directory, "directory")
        val normalizedEntry = normalizeRelativePath(entryPoint, "entryPoint")
        require(normalizedEntry.endsWith(".html", ignoreCase = true) || normalizedEntry.endsWith(".htm", ignoreCase = true)) {
            "Web app entryPoint must be an HTML file"
        }
        return Artifact(
            id = id,
            name = normalizedName,
            type = ArtifactType.fromCode(type),
            directory = normalizedDirectory,
            entryPoint = normalizedEntry,
            description = description.trim().replace(Regex("\\s+"), " ").take(MaxDescriptionChars),
        )
    }

    private fun normalizeRelativePath(value: String, field: String): String {
        require(value.isNotBlank() && !value.startsWith('/') && '\\' !in value && '\u0000' !in value) {
            "Artifact $field must be a relative path"
        }
        val segments = value.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Invalid artifact $field" }
        return segments.joinToString("/")
    }

    private fun normalizeWorkspaceDirectory(value: String): String {
        require(value.startsWith("/workspace/")) { "sourceDirectory must be inside /workspace" }
        val relative = normalizeRelativePath(value.removePrefix("/workspace/"), "sourceDirectory")
        return "/workspace/$relative"
    }

    @Serializable
    private data class StoredArtifactManifest(
        val version: Int = CurrentManifestVersion,
        val artifacts: List<StoredArtifact> = emptyList(),
    )

    @Serializable
    private data class StoredArtifact(
        val id: String,
        val name: String,
        val type: String,
        val directory: String,
        @SerialName("entry_point")
        val entryPoint: String = "index.html",
        val description: String = "",
    )

    private companion object {
        const val CurrentManifestVersion = 1
        const val MaxManifestBytes = 1_048_576
        const val MaxArtifacts = 1_000
        const val MaxNameChars = 128
        const val MaxDescriptionChars = 1_024
        const val MaxArtifactFiles = 10_000
        const val MaxArtifactBytes = 16_777_216L
        val IdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
