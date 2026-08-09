package ai.meteor.kcode.artifact

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ArtifactType(val code: String) {
    WebApp("web_app"),
    ;

    companion object {
        fun fromCode(code: String): ArtifactType = requireNotNull(entries.singleOrNull { it.code == code }) {
            "Unsupported artifact type: $code"
        }
    }
}

data class Artifact(
    val id: String,
    val name: String,
    val type: ArtifactType,
    val directory: String,
    val entryPoint: String,
    val description: String,
) {
    val entryPath: String
        get() = "$ArtifactResourcesRoot/$directory/$entryPoint"
}

interface ArtifactFileStore {
    suspend fun readText(path: String): String?
    suspend fun exists(path: String): Boolean
}

data class ArtifactFileEntry(
    val path: String,
    val directory: Boolean,
    val size: Long,
)

interface MutableArtifactFileStore : ArtifactFileStore {
    suspend fun readBytes(path: String): ByteArray?
    suspend fun writeBytesAtomically(path: String, contents: ByteArray)
    suspend fun list(path: String): List<ArtifactFileEntry>
    suspend fun deleteTree(path: String)
    suspend fun moveTree(source: String, target: String)
}

interface ArtifactRepository {
    suspend fun list(): List<Artifact>
}

data class SaveWebArtifactRequest(
    val id: String,
    val name: String,
    val sourceDirectory: String,
    val entryPoint: String = "index.html",
    val description: String = "",
)

interface MutableArtifactRepository : ArtifactRepository {
    suspend fun saveWebApp(request: SaveWebArtifactRequest): Artifact
}

class FileArtifactRepository(
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
        runCatching {
            copyDirectory(mutableStore, sourceDirectory, staging)
            require(mutableStore.exists("$staging/${artifact.entryPoint}")) {
                "Web app entry does not exist in source directory: ${artifact.entryPoint}"
            }
            mutableStore.moveTree(staging, target)
            val updated = manifest.copy(artifacts = manifest.artifacts + candidate)
            val encoded = json.encodeToString(updated).encodeToByteArray()
            require(encoded.size <= MaxManifestBytes) { "Artifact manifest is too large" }
            mutableStore.writeBytesAtomically(ArtifactManifestPath, encoded)
        }.onFailure {
            runCatching { mutableStore.deleteTree(staging) }
            runCatching { mutableStore.deleteTree(target) }
        }.getOrThrow()
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

object EmptyArtifactRepository : ArtifactRepository {
    override suspend fun list(): List<Artifact> = emptyList()
}

const val ArtifactManifestPath = "/workspace/artifacts/manifest.json"
const val ArtifactResourcesRoot = "/workspace/artifacts/resources"
