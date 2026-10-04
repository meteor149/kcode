package ai.meteor.kcode.artifact

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



const val ArtifactManifestPath = "/workspace/artifacts/manifest.json"
const val ArtifactResourcesRoot = "/workspace/artifacts/resources"
