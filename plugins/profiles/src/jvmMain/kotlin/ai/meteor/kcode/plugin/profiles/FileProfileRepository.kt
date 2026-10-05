package ai.meteor.kcode.plugin.profiles

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Immutable generations become visible only through one atomically replaced authority file. */
class FileProfileRepository(directory: File) : ProfileGenerationRepository {
    private val root = Files.createDirectories(directory.toPath()).toRealPath()
    private val mutex = processLocks.computeIfAbsent(root) { Mutex() }
    private val json = Json { prettyPrint = true; encodeDefaults = true }

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
        return checkedDirectory(root.resolve(id), create)
    }

    private fun checkedDirectory(path: Path, create: Boolean = false): Path {
        require(!Files.isSymbolicLink(path)) { "Invalid profile directory" }
        if (create) Files.createDirectories(path)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Invalid profile directory" }
        }
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

    private fun write(path: Path, text: String, replace: Boolean = true) {
        checkFile(path)
        require(replace || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "Generation already exists" }
        val bytes = text.encodeToByteArray()
        require(bytes.size <= MaxBytes) { "Profile document is too large" }
        val temporary = Files.createTempFile(path.parent, "profile-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output -> output.write(bytes); output.fd.sync() }
            if (replace) Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            // Cleanup cannot turn a successful atomic publication into an apparent failure.
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun decodeGeneration(text: String, id: String): CommittedProfileGeneration {
        val document = json.parseToJsonElement(text).jsonObject
        // Early format 1 writers omitted the default formatVersion field.
        val versioned = if ("formatVersion" in document) document else
            JsonObject(document + ("formatVersion" to JsonPrimitive(1)))
        return json.decodeFromJsonElement<CommittedProfileGeneration>(versioned).also { value ->
            require(value.definition.id == id) { "Profile identity mismatch" }
            value.validate(restoring = true)
        }
    }

    private fun generationPath(id: String, document: String, create: Boolean = false): Path {
        require(document.matches(DocumentId)) { "Invalid generation document identity" }
        return checkedDirectory(profileDirectory(id, create).resolve("generations"), create).resolve("$document.json")
    }

    private fun load(id: String, pointer: GenerationPointer): CommittedProfileGeneration =
        decodeGeneration(requireNotNull(read(generationPath(id, pointer.document))) { "Committed generation is missing" }, id).also {
            require(it.generation == pointer.generation) { "Generation pointer mismatch" }
        }

    private fun stage(value: CommittedProfileGeneration): GenerationPointer {
        val pointer = GenerationPointer(value.generation, UUID.randomUUID().toString())
        write(generationPath(value.definition.id, pointer.document, create = true), json.encodeToString(value), replace = false)
        return pointer
    }

    private fun authority(): Authority {
        val existing = read(root.resolve(StateFilename))
        if (existing != null) return json.decodeFromString<Authority>(existing).also(::validateAuthority)
        // Import once. Legacy documents remain read-only migration evidence; orphans are never adopted.
        val profiles = linkedMapOf<String, ProfileRecord>()
        Files.newDirectoryStream(root).use { entries ->
            entries.filter { !Files.isSymbolicLink(it) && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sortedBy { it.fileName.toString() }.forEach { path ->
                    val id = path.fileName.toString()
                    profileDirectory(id)
                    val legacy = read(path.resolve("committed.json"))?.let { decodeGeneration(it, id) }
                    val draft = read(path.resolve("profile.json")) != null
                    if (legacy != null || draft) profiles[id] = ProfileRecord(draft, legacy?.let { listOf(stage(it)) }.orEmpty())
                }
        }
        val selection = read(root.resolve("selection.json"))?.let(json::parseToJsonElement)
        val selected = if (selection == null || selection == JsonNull) null else selection.jsonPrimitive.content
        return Authority(profiles = profiles, selected = selected).also {
            validateAuthority(it)
            write(root.resolve(StateFilename), json.encodeToString(it))
        }
    }

    private fun validateAuthority(value: Authority) {
        require(value.formatVersion == 1 && value.revision >= 0) { "Invalid Profile repository state" }
        value.profiles.forEach { (id, record) ->
            profileDirectory(id)
            require(record.draft || record.generations.isNotEmpty()) { "Empty Profile record" }
            var previous = 0L
            record.generations.forEach { pointer ->
                require(pointer.generation > previous && pointer.document.matches(DocumentId)) { "Invalid generation history" }
                previous = pointer.generation
            }
            require(record.generations.map { it.document }.distinct().size == record.generations.size) { "Duplicate generation document" }
        }
        require(value.selected == null || value.profiles[value.selected]?.generations?.isNotEmpty() == true) {
            "Selected profile has no committed generation"
        }
    }

    private fun publish(previous: Authority, next: Authority) {
        require(previous.revision < Long.MAX_VALUE) { "Profile repository revision exhausted" }
        val updated = next.copy(revision = previous.revision + 1)
        validateAuthority(updated)
        write(root.resolve(StateFilename), json.encodeToString(updated))
    }

    override suspend fun list(): List<String> = access { authority().profiles.keys.sorted() }

    override suspend fun loadDraft(id: String): ProfileDefinition? = access {
        val path = profileDirectory(id)
        if (authority().profiles[id]?.draft != true) null else {
            val text = requireNotNull(read(path.resolve("profile.json"))) { "Profile draft is missing" }
            json.decodeFromString<ProfileDefinition>(text).also { value ->
                value.validate()
                require(value.id == id) { "Profile identity mismatch" }
            }
        }
    }

    override suspend fun saveDraft(definition: ProfileDefinition) = access(atomic = true) {
        definition.validate()
        val previous = authority()
        write(profileDirectory(definition.id, create = true).resolve("profile.json"), json.encodeToString(definition))
        val record = previous.profiles[definition.id] ?: ProfileRecord()
        publish(previous, previous.copy(profiles = previous.profiles + (definition.id to record.copy(draft = true))))
    }

    override suspend fun loadCommitted(id: String): CommittedProfileGeneration? = access {
        profileDirectory(id)
        authority().profiles[id]?.generations?.lastOrNull()?.let { load(id, it) }
    }

    override suspend fun generations(id: String): List<Long> = access {
        profileDirectory(id)
        authority().profiles[id]?.generations.orEmpty().map { it.generation }
    }

    override suspend fun loadGeneration(id: String, generation: Long): CommittedProfileGeneration? = access {
        profileDirectory(id)
        authority().profiles[id]?.generations?.firstOrNull { it.generation == generation }?.let { load(id, it) }
    }

    private fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?, expectedRevision: Long?) {
        value.validate()
        val previous = authority()
        require(expectedRevision == null || previous.revision == expectedRevision) { "Profile repository changed; refresh before switching" }
        val record = previous.profiles[value.definition.id] ?: ProfileRecord()
        val current = record.generations.lastOrNull()
        require(current?.generation == expectedGeneration) { "Profile generation changed; refresh before applying" }
        require((expectedGeneration ?: 0L) < Long.MAX_VALUE && value.generation == (expectedGeneration ?: 0L) + 1L) {
            "Profile generation must advance exactly once"
        }
        current?.let { load(value.definition.id, it) }
        val pointer = stage(value)
        publish(previous, previous.copy(
            profiles = previous.profiles + (value.definition.id to record.copy(generations = record.generations + pointer)),
            selected = if (expectedRevision == null) previous.selected else value.definition.id,
        ))
    }

    override suspend fun commit(value: CommittedProfileGeneration, expectedGeneration: Long?) = access(atomic = true) {
        commit(value, expectedGeneration, expectedRevision = null)
    }

    override suspend fun commitAndSelect(value: CommittedProfileGeneration, expectedGeneration: Long?, expectedRevision: Long) = access(atomic = true) {
        commit(value, expectedGeneration, expectedRevision)
    }

    override suspend fun state(): ProfileRepositoryState = access {
        val current = authority()
        ProfileRepositoryState(current.revision, current.selected?.let { id -> load(id, current.profiles.getValue(id).generations.last()) })
    }

    override suspend fun selected(): String? = state().selected?.definition?.id

    override suspend fun select(id: String?) = access(atomic = true) {
        val previous = authority()
        if (id != null) {
            profileDirectory(id)
            val pointer = requireNotNull(previous.profiles[id]?.generations?.lastOrNull()) { "Cannot select an uncommitted profile" }
            load(id, pointer)
        }
        if (previous.selected != id) publish(previous, previous.copy(selected = id))
    }

    override suspend fun remove(id: String) = access(atomic = true) {
        profileDirectory(id)
        val previous = authority()
        require(previous.selected != id) { "Cannot remove the selected profile" }
        if (id in previous.profiles) publish(previous, previous.copy(profiles = previous.profiles - id))
        // Logical deletion is final. Retained metadata is unreachable and cannot reappear on reopen.
        // Physical reclamation is separate from publication and never deletes provider business data.
    }

    @Serializable
    private data class GenerationPointer(val generation: Long, val document: String)

    @Serializable
    private data class ProfileRecord(val draft: Boolean = false, val generations: List<GenerationPointer> = emptyList())

    @Serializable
    private data class Authority(
        val formatVersion: Int = 1,
        val revision: Long = 0,
        val selected: String? = null,
        val profiles: Map<String, ProfileRecord> = emptyMap(),
    )

    private companion object {
        const val MaxBytes = 1_048_576
        const val StateFilename = ".profile-state.json"
        val DocumentId = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val processLocks = ConcurrentHashMap<Path, Mutex>()
    }
}
