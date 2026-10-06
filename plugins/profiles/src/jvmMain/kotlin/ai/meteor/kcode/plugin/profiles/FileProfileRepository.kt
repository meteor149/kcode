package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileSummary
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
class FileProfileRepository(directory: File) : ProfileGenerationRepository, ProfileRepositoryRecovery {
    private val root by lazy { Files.createDirectories(directory.toPath()).toRealPath() }
    private val mutex get() = processLocks.computeIfAbsent(root) { Mutex() }
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private suspend fun <T> access(atomic: Boolean = false, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withContext(if (atomic) Dispatchers.IO + NonCancellable else Dispatchers.IO) {
                    val lockFile = root.resolve("repository.lock")
                    checkFile(lockFile)
                    FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                        channel.lock().use { block() }
                    }
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

    private fun write(path: Path, text: String, replace: Boolean = true, maxBytes: Int = MaxBytes) {
        checkFile(path)
        require(replace || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "Generation already exists" }
        val bytes = text.encodeToByteArray()
        require(bytes.size <= maxBytes) { "Profile document is too large" }
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
        require(!Files.exists(root.resolve(CheckpointFilename), LinkOption.NOFOLLOW_LINKS) && !hasVersionedDocuments()) {
            "Profile authority is missing; use explicit repository recovery"
        }
        // Import once. Legacy documents remain read-only migration evidence; orphans are never adopted.
        val profiles = linkedMapOf<String, ProfileRecord>()
        Files.newDirectoryStream(root).use { entries ->
            entries.filter { !it.fileName.toString().startsWith('.') && !Files.isSymbolicLink(it) && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
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
        require(value.formatVersion in 1..2 && value.revision >= 0) { "Invalid Profile repository state" }
        value.profiles.forEach { (id, record) ->
            profileDirectory(id)
            require(record.draft || record.generations.isNotEmpty()) { "Empty Profile record" }
            require(record.draftDocument == null || record.draft && record.draftDocument.matches(DocumentId)) { "Invalid draft pointer" }
            require(record.draftName == null || record.draft && record.draftName.isNotBlank()) { "Invalid draft name" }
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
        val updated = next.copy(formatVersion = 2, revision = previous.revision + 1)
        validateAuthority(updated)
        saveCheckpoint(previous)
        write(root.resolve(StateFilename), json.encodeToString(updated))
    }

    private fun hasVersionedDocuments(): Boolean = Files.newDirectoryStream(root).use { entries ->
        entries.any { path -> !path.fileName.toString().startsWith('.') &&
            !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            listOf("generations", "drafts").any { Files.exists(path.resolve(it), LinkOption.NOFOLLOW_LINKS) } }
    }

    private fun raw(path: Path, limit: Int = MaxEvidenceBytes): ByteArray? {
        checkFile(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.newInputStream(path).use { it.readNBytes(limit + 1) }.also {
            require(it.size <= limit) { "Profile recovery evidence exceeds its size limit" }
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun documentChecks(value: Authority): Map<String, (ByteArray) -> Unit> {
        validateAuthority(value)
        return buildMap {
            value.profiles.forEach { (id, record) ->
                record.generations.forEach { pointer ->
                    val path = generationPath(id, pointer.document)
                    put(root.relativize(path).toString().replace('\\', '/')) { bytes ->
                        val generation = decodeGeneration(bytes.decodeToString(throwOnInvalidSequence = true), id)
                        require(generation.generation == pointer.generation) { "Generation pointer mismatch" }
                    }
                }
                if (record.draft) {
                    val path = record.draftDocument?.let { draftPath(id, it) } ?: profileDirectory(id).resolve("profile.json")
                    put(root.relativize(path).toString().replace('\\', '/')) { bytes ->
                        val text = bytes.decodeToString(throwOnInvalidSequence = true)
                        val document = if (record.draftDocument == null) ProfileDraftDocument(json.decodeFromString<ProfileDefinition>(text))
                            else json.decodeFromString<ProfileDraftDocument>(text)
                        document.validate()
                        require(document.definition.id == id) { "Profile identity mismatch" }
                    }
                }
            }
        }
    }

    /** Only an authority already read from the published file becomes a checkpoint. */
    private fun saveCheckpoint(value: Authority) {
        val documents = documentChecks(value).keys.associateWith { relative ->
            digest(checkNotNull(raw(root.resolve(relative), MaxBytes)) { "Published Profile document is missing" })
        }
        write(root.resolve(CheckpointFilename), json.encodeToString(Checkpoint(authority = value, documents = documents)), maxBytes = MaxEvidenceBytes)
    }

    private data class RecoveryInspection(
        val review: ProfileRepositoryRecoveryReview,
        val checkpoint: Authority?,
        val observed: Map<String, String>,
    )

    private suspend fun inspect(): RecoveryInspection? {
        val observed = linkedMapOf<String, String>()
        fun observe(relative: String): ByteArray? = raw(root.resolve(relative)).also {
            observed[relative] = it?.let(::digest) ?: "missing"
        }
        suspend fun validateDocuments(value: Authority, locked: Map<String, String>? = null) {
            val checks = documentChecks(value)
            require(locked == null || checks.keys == locked.keys) { "Checkpoint document set differs from authority" }
            checks.forEach { (relative, validate) ->
                currentCoroutineContext().ensureActive()
                val bytes = checkNotNull(observe(relative)) { "Published Profile document is missing" }
                require(bytes.size <= MaxBytes) { "Profile document is too large" }
                require(locked == null || digest(bytes) == locked[relative]) { "Checkpoint Profile document changed" }
                validate(bytes)
            }
        }
        val primary = observe(StateFilename)
        val failure = try {
            val bytes = checkNotNull(primary) { "Profile authority is missing" }
            require(bytes.size <= MaxBytes) { "Profile document is too large" }
            validateDocuments(json.decodeFromString<Authority>(bytes.decodeToString(throwOnInvalidSequence = true)))
            return null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { error.message ?: error.toString() }
        var checkpoint: Authority? = null
        val checkpointFailure = try {
            val bytes = checkNotNull(observe(CheckpointFilename)) { "No published checkpoint is available" }
            val saved = json.decodeFromString<Checkpoint>(bytes.decodeToString(throwOnInvalidSequence = true))
            require(saved.formatVersion == 1) { "Unsupported Profile checkpoint" }
            validateDocuments(saved.authority, saved.documents)
            checkpoint = saved.authority
            null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { error.message ?: error.toString() }
        val fingerprint = digest(json.encodeToString<Map<String, String>>(observed.toSortedMap()).encodeToByteArray())
        return RecoveryInspection(ProfileRepositoryRecoveryReview(fingerprint, failure,
            checkpoint?.let(::catalogue), checkpointFailure), checkpoint, observed)
    }

    override suspend fun inspectRecovery(): ProfileRepositoryRecoveryReview? = access { inspect()?.review }

    override suspend fun repair(request: ProfileRepositoryRepairRequest): ProfileRepositoryRepairResult = access {
        val inspected = checkNotNull(inspect()) { "Profile repository is healthy; repair is unnecessary" }
        require(request.expectedFingerprint == inspected.review.fingerprint) { "Profile recovery inputs changed; review again" }
        var revision: Long
        do { revision = (1L shl 61) + SecureRandom().nextLong().ushr(3) }
        while (revision == inspected.checkpoint?.revision)
        val next = when (request.mode) {
            ProfileRepositoryRepairMode.RestoreCheckpoint -> checkNotNull(inspected.checkpoint) { "No verified checkpoint is available" }
                .copy(formatVersion = 2, revision = revision)
            // A fresh opaque revision epoch prevents replay of pre-repair revision handles.
            ProfileRepositoryRepairMode.StartEmpty -> Authority(revision = revision)
        }
        validateAuthority(next)
        val nextCatalogue = catalogue(next)
        currentCoroutineContext().ensureActive()
        val evidenceRoot = checkedDirectory(root.resolve(EvidenceDirectory), create = true)
        val evidenceId = UUID.randomUUID().toString()
        val evidence = checkedDirectory(evidenceRoot.resolve(evidenceId), create = true)
        inspected.observed.forEach { (relative, expected) ->
            currentCoroutineContext().ensureActive()
            val source = root.resolve(relative).normalize()
            require(source.startsWith(root) && source != root) { "Invalid recovery evidence path" }
            val bytes = raw(source)
            require((bytes?.let(::digest) ?: "missing") == expected) { "Profile recovery inputs changed; review again" }
            if (bytes != null) {
                val destination = evidence.resolve(relative).normalize()
                require(destination.startsWith(evidence) && destination != evidence) { "Invalid recovery evidence destination" }
                Files.createDirectories(destination.parent)
                FileOutputStream(destination.toFile()).use { output -> output.write(bytes); output.fd.sync() }
            }
        }
        write(evidence.resolve("review.json"), json.encodeToString(RecoveryEvidence(
            fingerprint = inspected.review.fingerprint, mode = request.mode.name, files = inspected.observed,
            revision = next.revision,
        )), replace = false, maxBytes = MaxEvidenceBytes)
        require(inspect()?.review?.fingerprint == request.expectedFingerprint) { "Profile recovery inputs changed; review again" }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            // The original bytes are durable before the single authority publication.
            write(root.resolve(StateFilename), json.encodeToString(next))
            ProfileRepositoryRepairResult(nextCatalogue, evidenceId)
        }
    }

    override suspend fun list(): List<String> = access { authority().profiles.keys.sorted() }

    private fun draftPath(id: String, document: String, create: Boolean = false): Path {
        require(document.matches(DocumentId)) { "Invalid draft document identity" }
        return checkedDirectory(profileDirectory(id, create).resolve("drafts"), create).resolve("$document.json")
    }

    private fun draft(id: String, record: ProfileRecord?): ProfileDraftDocument? {
        val directory = profileDirectory(id)
        if (record?.draft != true) return null
        val document = record.draftDocument
        val path = if (document == null) directory.resolve("profile.json") else draftPath(id, document)
        val text = requireNotNull(read(path)) { "Profile draft is missing" }
        val value = if (document == null) ProfileDraftDocument(json.decodeFromString<ProfileDefinition>(text))
            else json.decodeFromString<ProfileDraftDocument>(text)
        value.validate()
        require(value.definition.id == id) { "Profile identity mismatch" }
        return value
    }

    override suspend fun loadDraft(id: String): ProfileDefinition? = loadDraftDocument(id)?.definition
    override suspend fun loadDraftDocument(id: String): ProfileDraftDocument? = access {
        draft(id, authority().profiles[id])
    }

    private fun writeDraft(document: ProfileDraftDocument, previous: Authority, createOnly: Boolean) {
        document.validate()
        val id = document.definition.id
        require(!createOnly || id !in previous.profiles) { "Profile already exists" }
        val pointer = UUID.randomUUID().toString()
        write(draftPath(id, pointer, create = true), json.encodeToString(document.copy(formatVersion = 2)), replace = false)
        val record = previous.profiles[id] ?: ProfileRecord()
        publish(previous, previous.copy(profiles = previous.profiles + (id to record.copy(
            draft = true, draftDocument = pointer, draftName = document.definition.displayName))))
    }

    override suspend fun saveDraft(definition: ProfileDefinition) = access(atomic = true) {
        val previous = authority()
        val record = previous.profiles[definition.id]
        val base = record?.generations?.lastOrNull()?.let { load(definition.id, it) }
            ?: draft(definition.id, record)?.base
        val imported = draft(definition.id, record)?.imported.takeIf { base == null }
        writeDraft(ProfileDraftDocument(definition, base, imported = imported), previous, createOnly = false)
    }

    override suspend fun writeDraft(document: ProfileDraftDocument, expectedRevision: Long, createOnly: Boolean) = access(atomic = true) {
        val previous = authority()
        require(previous.revision == expectedRevision) { "Profile repository changed; refresh before editing" }
        writeDraft(document, previous, createOnly)
    }

    private fun catalogue(value: Authority): ProfileCatalogue = ProfileCatalogue(
        value.revision, value.selected, value.profiles.entries.sortedBy { it.key }.map { (id, record) ->
            val latest = record.generations.lastOrNull()?.let { load(id, it) }
            ProfileSummary(id, latest?.definition?.displayName ?: record.draftName ?: id, record.draft, latest?.generation, record.draftName)
        },
    )

    override suspend fun catalogue(): ProfileCatalogue = access { catalogue(authority()) }

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

    private fun removeRecord(id: String, expectedRevision: Long?) {
        profileDirectory(id)
        val previous = authority()
        require(expectedRevision == null || previous.revision == expectedRevision) { "Profile repository changed; refresh before deleting" }
        require(previous.selected != id) { "Cannot remove the selected profile" }
        if (id in previous.profiles) publish(previous, previous.copy(profiles = previous.profiles - id))
        // Logical deletion is final. Retained metadata is unreachable and cannot reappear on reopen.
        // Physical reclamation is separate from publication and never deletes provider business data.
    }

    override suspend fun remove(id: String) = access(atomic = true) { removeRecord(id, null) }
    override suspend fun remove(id: String, expectedRevision: Long) = access(atomic = true) { removeRecord(id, expectedRevision) }

    @Serializable
    private data class GenerationPointer(val generation: Long, val document: String)

    @Serializable
    private data class ProfileRecord(
        val draft: Boolean = false,
        val generations: List<GenerationPointer> = emptyList(),
        val draftDocument: String? = null,
        val draftName: String? = null,
    )

    @Serializable
    private data class Authority(
        val formatVersion: Int = 2,
        val revision: Long = 0,
        val selected: String? = null,
        val profiles: Map<String, ProfileRecord> = emptyMap(),
    )

    @Serializable
    private data class Checkpoint(
        val formatVersion: Int = 1,
        val authority: Authority,
        val documents: Map<String, String>,
    )

    @Serializable
    private data class RecoveryEvidence(
        val formatVersion: Int = 1,
        val fingerprint: String,
        val mode: String,
        val files: Map<String, String>,
        val revision: Long,
    )

    private companion object {
        const val MaxBytes = 1_048_576
        const val StateFilename = ".profile-state.json"
        const val CheckpointFilename = ".profile-checkpoint.json"
        const val EvidenceDirectory = ".profile-recovery"
        const val MaxEvidenceBytes = 16_777_216
        val DocumentId = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val processLocks = ConcurrentHashMap<Path, Mutex>()
    }
}
