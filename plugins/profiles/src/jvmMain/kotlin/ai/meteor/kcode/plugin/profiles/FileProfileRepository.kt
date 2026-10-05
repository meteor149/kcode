package ai.meteor.kcode.plugin.profiles

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** App-private JSON repository. Committed definition, lock and runtime snapshot share one file. */
class FileProfileRepository(directory: File) : ProfileRepository {
    private val root = Files.createDirectories(directory.toPath()).toRealPath()
    private val mutex = processLocks.computeIfAbsent(root) { Mutex() }
    private val json = Json { prettyPrint = true }

    private suspend fun <T> access(atomic: Boolean = false, block: () -> T): T = mutex.withLock {
        withContext(if (atomic) Dispatchers.IO + NonCancellable else Dispatchers.IO) {
            val lockFile = root.resolve("repository.lock")
            checkFile(lockFile)
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    private fun profileDirectory(id: String, create: Boolean = false): Path {
        ProfileDefinition(id = id).validate()
        require(id !in setOf("repository.lock", "selection.json")) { "Reserved profile id" }
        val path = root.resolve(id)
        require(!Files.isSymbolicLink(path)) { "Invalid profile directory" }
        if (create) Files.createDirectories(path)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Invalid profile directory" }
        return path
    }

    private fun checkFile(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Invalid profile file" }
        }
    }

    private fun read(path: Path): String? {
        checkFile(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = Files.newInputStream(path).use { it.readNBytes(MaxBytes + 1) }
        require(bytes.size <= MaxBytes) { "Profile document is too large" }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private fun write(path: Path, text: String) {
        checkFile(path)
        val bytes = text.encodeToByteArray()
        require(bytes.size <= MaxBytes) { "Profile document is too large" }
        val temporary = Files.createTempFile(path.parent, "profile-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // Cleanup cannot turn a successful atomic publication into an apparent failure.
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun committed(id: String): CommittedProfileGeneration? =
        read(profileDirectory(id).resolve("committed.json"))?.let {
            json.decodeFromString<CommittedProfileGeneration>(it).also { value ->
                require(value.definition.id == id) { "Profile identity mismatch" }
                value.validate(restoring = true)
            }
        }

    override suspend fun list(): List<String> = access {
        Files.newDirectoryStream(root).use { entries ->
            entries.filter { !Files.isSymbolicLink(it) && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .map { it.fileName.toString() }.sorted()
        }
    }

    override suspend fun loadDraft(id: String): ProfileDefinition? = access {
        read(profileDirectory(id).resolve("profile.json"))?.let {
            json.decodeFromString<ProfileDefinition>(it).also { value ->
                value.validate()
                require(value.id == id) { "Profile identity mismatch" }
            }
        }
    }

    override suspend fun saveDraft(definition: ProfileDefinition) = access(atomic = true) {
        definition.validate()
        write(profileDirectory(definition.id, create = true).resolve("profile.json"), json.encodeToString(definition))
    }

    override suspend fun loadCommitted(id: String): CommittedProfileGeneration? = access { committed(id) }

    override suspend fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?) = access(atomic = true) {
        value.validate()
        val previous = committed(value.definition.id)
        require(previous?.generation == expectedGeneration) { "Profile generation changed; refresh before applying" }
        require(value.generation == (expectedGeneration ?: 0L) + 1L) { "Profile generation must advance exactly once" }
        write(profileDirectory(value.definition.id, create = true).resolve("committed.json"), json.encodeToString(value))
    }

    override suspend fun selected(): String? = access {
        val value = read(root.resolve("selection.json"))?.let(json::parseToJsonElement)
        if (value == null || value == JsonNull) null else value.jsonPrimitive.content.also {
            require(committed(it) != null) { "Selected profile has no committed generation" }
        }
    }

    override suspend fun select(id: String?) = access(atomic = true) {
        if (id != null) require(committed(id) != null) { "Cannot select an uncommitted profile" }
        write(root.resolve("selection.json"), (id?.let(::JsonPrimitive) ?: JsonNull).toString())
    }

    override suspend fun remove(id: String) = access(atomic = true) {
        val selected = read(root.resolve("selection.json"))?.let(json::parseToJsonElement)
        require(selected == null || selected == JsonNull || selected.jsonPrimitive.content != id) { "Cannot remove the selected profile" }
        val path = profileDirectory(id)
        if (Files.exists(path)) Files.newDirectoryStream(path).use { entries ->
            require(entries.all { it.fileName.toString() in setOf("profile.json", "committed.json") }) { "Profile directory contains unknown files" }
        }
        listOf("profile.json", "committed.json").forEach { name ->
            val file = path.resolve(name)
            checkFile(file)
            Files.deleteIfExists(file)
        }
        if (Files.exists(path)) Files.delete(path)
        Unit
    }

    private companion object {
        const val MaxBytes = 1_048_576
        val processLocks = ConcurrentHashMap<Path, Mutex>()
    }
}
