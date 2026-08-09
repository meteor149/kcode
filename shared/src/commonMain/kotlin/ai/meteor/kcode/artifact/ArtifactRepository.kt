package ai.meteor.kcode.artifact

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

interface ArtifactRepository {
    suspend fun list(): List<Artifact>
}

class FileArtifactRepository(
    private val store: ArtifactFileStore,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : ArtifactRepository {
    override suspend fun list(): List<Artifact> {
        val manifestText = store.readText(ArtifactManifestPath) ?: return emptyList()
        require(manifestText.encodeToByteArray().size <= MaxManifestBytes) { "Artifact manifest is too large" }
        val manifest = json.decodeFromString<StoredArtifactManifest>(manifestText)
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
        val IdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

object EmptyArtifactRepository : ArtifactRepository {
    override suspend fun list(): List<Artifact> = emptyList()
}

const val ArtifactManifestPath = "/workspace/artifacts/manifest.json"
const val ArtifactResourcesRoot = "/workspace/artifacts/resources"
