package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleReference
import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandHandle
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandStatus
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionState
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileModuleSummary
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfilePreview
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableImport
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleImport
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

enum class ProfileUiFailure { InvalidDocument, OperationFailed }
enum class ProfileExchangeResult { Imported, Exported }

data class ProfileUiState(
    val catalogue: ProfileCatalogue? = null,
    val target: ProfileTarget? = null,
    val document: String = "",
    val savedDocument: String = "",
    val documentRevision: Long? = null,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val preview: ProfilePreview? = null,
    val history: List<ProfileCompositionState> = emptyList(),
    val modules: List<ProfileModuleSummary> = emptyList(),
    val command: ProfileCommandStatus? = null,
    val failure: ProfileUiFailure? = null,
    val leaveRequested: Boolean = false,
    val exchange: ProfileExchangeResult? = null,
)

/** Presentation owns queries/observers; the native host owns accepted composition commands. */
class ProfileUiSession(private val client: ProfileManagementClient) {
    private val owner = PluginOperationOwner("profile-ui")
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }
    private val mutableState = MutableStateFlow(ProfileUiState())
    private data class PendingLeave(val target: ProfileTarget?, val revision: Long?, val document: String, val proceed: () -> Unit)
    private var pendingLeave: PendingLeave? = null
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()

    fun edit(document: String) {
        owner.requireOpen()
        check(!state.value.busy && state.value.target != null) { "Profile editor is not available" }
        check(state.value.target?.source != ProfileSource.History) { "Clone historical intent before editing it" }
        mutableState.value = state.value.copy(document = document, dirty = true, preview = null, failure = null)
    }

    suspend fun refresh() = operation {
        val catalogue = client.catalogue()
        val current = state.value
        if (current.dirty) {
            // Keep the original editor revision: refresh cannot authorize overwriting intervening edits.
            mutableState.value = current.copy(catalogue = catalogue, preview = null)
        } else {
            val target = current.target?.takeIf { value -> catalogue.profiles.any { it.id == value.profileId } }
                ?: (catalogue.activeProfileId ?: catalogue.selectedProfileId ?: catalogue.profiles.firstOrNull()?.id)
                    ?.let { id -> defaultTarget(catalogue, id) }
            if (target == null) mutableState.value = current.copy(catalogue = catalogue, target = null,
                document = "", savedDocument = "", documentRevision = catalogue.revision, preview = null, history = emptyList())
            else load(catalogue, target)
        }
    }

    suspend fun select(target: ProfileTarget, discardEdits: Boolean = false) = operation {
        check(!state.value.dirty || discardEdits) { "Unsaved Profile edits" }
        load(client.catalogue(), target)
    }

    suspend fun create(id: String, displayName: String) = operation {
        check(!state.value.dirty) { "Unsaved Profile edits" }
        val catalogue = requireNotNull(state.value.catalogue)
        val definition = ProfileDefinition(id = id, displayName = displayName,
            dataScope = ProfileDataScope(workspace = "profile"))
        definition.validate()
        val next = client.writeDraft(ProfileDraftWrite(definition, catalogue.revision, createOnly = true))
        load(next, ProfileTarget(id, ProfileSource.Draft))
    }

    suspend fun clone(id: String, displayName: String) = operation {
        check(!state.value.dirty) { "Unsaved Profile edits" }
        val current = state.value
        val next = client.clone(ProfileCloneRequest(requireNotNull(current.target), id,
            requireNotNull(current.catalogue).revision, displayName))
        load(next, ProfileTarget(id, ProfileSource.Draft))
    }

    internal suspend fun importFile(files: ProfileDocumentFiles, id: String, displayName: String, title: String) = fileOperation {
        val current = state.value
        check(!current.dirty) { "Unsaved Profile edits" }
        ProfileDefinition(id = id, displayName = displayName).validate()
        val revision = requireNotNull(current.catalogue).revision
        val document = files.read(title) ?: return@fileOperation
        val next = client.importPortable(ProfilePortableImport(document, id, revision, displayName))
        val definition = requireNotNull(client.draft(id)) { "Imported draft is unavailable" }
        check(client.catalogue().revision == next.revision) { "Profile repository changed; refresh" }
        val text = encode(definition)
        mutableState.value = state.value.copy(catalogue = next, target = ProfileTarget(id, ProfileSource.Draft),
            document = text, savedDocument = text, documentRevision = next.revision, dirty = false,
            preview = null, history = emptyList(), exchange = ProfileExchangeResult.Imported)
    }

    internal suspend fun exportFile(files: ProfileDocumentFiles, title: String) = fileOperation {
        val current = state.value
        check(!current.dirty) { "Unsaved Profile edits" }
        val target = requireNotNull(current.target)
        require(target.source != ProfileSource.Draft) { "Activate a draft before export" }
        val document = client.exportPortable(ProfilePortableExport(target, requireNotNull(current.documentRevision)))
        if (files.write(title, "${target.profileId}.kcode-profile.json", document)) {
            mutableState.value = state.value.copy(exchange = ProfileExchangeResult.Exported)
        }
    }

    internal suspend fun importBundles(files: ProfileDocumentFiles, id: String, displayName: String, title: String) = fileOperation {
        val current = state.value
        check(!current.dirty) { "Unsaved Profile edits" }
        ProfileDefinition(id = id, displayName = displayName).validate()
        val revision = requireNotNull(current.catalogue).revision
        files.readBundles(title) { archives ->
            val next = client.importBundles(ProfileBundleImport(archives, id, revision, displayName))
            val definition = requireNotNull(client.draft(id)) { "Imported draft is unavailable" }
            check(client.catalogue().revision == next.revision) { "Profile repository changed; refresh" }
            val text = encode(definition)
            mutableState.value = state.value.copy(catalogue = next, target = ProfileTarget(id, ProfileSource.Draft),
                document = text, savedDocument = text, documentRevision = next.revision, dirty = false,
                preview = null, history = emptyList(), exchange = ProfileExchangeResult.Imported)
        }
    }

    suspend fun save() = operation {
        saveDocument(state.value)
    }

    private suspend fun saveDocument(current: ProfileUiState): Boolean {
        check(current.target?.source != ProfileSource.History) { "Clone historical intent before editing it" }
        val definition = try {
            json.decodeFromString(ProfileDefinition.serializer(), current.document).also {
                it.validate()
                require(it.id == current.target?.profileId) { "Editor cannot change Profile identity" }
            }
        } catch (error: IllegalArgumentException) {
            mutableState.value = current.copy(failure = ProfileUiFailure.InvalidDocument)
            return false
        }
        val next = client.writeDraft(ProfileDraftWrite(definition, requireNotNull(current.documentRevision)))
        val document = encode(definition)
        mutableState.value = current.copy(catalogue = next, target = ProfileTarget(definition.id, ProfileSource.Draft),
            document = document, savedDocument = document, documentRevision = next.revision, dirty = false, preview = null)
        return true
    }

    fun requestLeave(proceed: () -> Unit) {
        owner.requireOpen()
        val current = state.value
        if (!current.dirty) { proceed(); return }
        if (pendingLeave == null) pendingLeave = PendingLeave(current.target, current.documentRevision, current.document, proceed)
        mutableState.value = current.copy(leaveRequested = true, failure = null)
    }

    fun cancelLeave() {
        owner.requireOpen()
        check(!state.value.busy) { "Profile editor is busy" }
        pendingLeave = null
        mutableState.value = state.value.copy(leaveRequested = false)
    }

    fun discardAndLeave() {
        owner.requireOpen()
        val pending = pendingLeave ?: return
        check(!state.value.busy) { "Profile editor is busy" }
        requirePending(pending)
        pendingLeave = null
        mutableState.value = state.value.copy(document = state.value.savedDocument, dirty = false,
            preview = null, leaveRequested = false, failure = null)
        pending.proceed()
    }

    suspend fun saveAndLeave() {
        var proceed: (() -> Unit)? = null
        operation {
            val pending = pendingLeave ?: return@operation
            requirePending(pending)
            if (saveDocument(state.value)) {
                pendingLeave = null
                mutableState.value = state.value.copy(leaveRequested = false)
                proceed = pending.proceed
            }
        }
        // Navigation is outside the owned query/lock, after durable draft publication.
        proceed?.invoke()
    }

    private fun requirePending(pending: PendingLeave) {
        val current = state.value
        check(current.target == pending.target && current.documentRevision == pending.revision && current.document == pending.document) {
            "Profile editor changed; request navigation again"
        }
    }

    suspend fun preview() = operation {
        val current = state.value
        check(!current.dirty) { "Save Profile before preview" }
        val result = client.preview(requireNotNull(current.target))
        check(result.revision == current.documentRevision) { "Profile changed; reload before preview" }
        mutableState.value = current.copy(preview = result)
    }

    /** Structured forms save portable intent to the draft; the active tree is never mutated here. */
    suspend fun appendOperation(target: ProfileTarget, revision: Long, operation: ProfileOperation) {
        val detached = json.decodeFromString(ProfileOperation.serializer(),
            json.encodeToString(ProfileOperation.serializer(), operation))
        changeDraft(target, revision) { it.copy(patches = it.patches + detached) }
    }

    suspend fun reorderBundles(target: ProfileTarget, revision: Long, bundles: List<ProfileBundleReference>) {
        val detached = bundles.toList()
        changeDraft(target, revision) { it.copy(bundles = detached) }
    }

    suspend fun rename(target: ProfileTarget, revision: Long, name: String) {
        changeDraft(target, revision) { it.copy(displayName = name) }
    }

    private suspend fun changeDraft(
        target: ProfileTarget,
        revision: Long,
        change: (ProfileDefinition) -> ProfileDefinition,
    ) = operation {
        val current = state.value
        check(!current.dirty && current.target == target && current.documentRevision == revision) {
            "Profile editor changed; reload the form"
        }
        check(target.source != ProfileSource.History) { "Clone historical intent before editing it" }
        val definition = change(json.decodeFromString(ProfileDefinition.serializer(), current.document))
        definition.validate()
        val next = client.writeDraft(ProfileDraftWrite(definition, revision))
        // Publish the saved document before preview: a later query failure cannot hide a durable save.
        val document = encode(definition)
        mutableState.value = current.copy(catalogue = next, target = ProfileTarget(definition.id, ProfileSource.Draft),
            document = document, savedDocument = document, documentRevision = next.revision, dirty = false, preview = null)
        load(next, ProfileTarget(definition.id, ProfileSource.Draft))
    }

    suspend fun delete(
        target: ProfileTarget,
        expectedRevision: Long,
    ) = operation {
        val current = state.value
        check(!current.dirty) { "Unsaved Profile edits" }
        check(current.target == target && current.catalogue?.revision == expectedRevision) { "Deletion selection changed" }
        val next = client.delete(target.profileId, expectedRevision)
        mutableState.value = ProfileUiState(catalogue = next, busy = true)
        val id = next.activeProfileId ?: next.selectedProfileId ?: next.profiles.firstOrNull()?.id
        if (id != null) load(next, defaultTarget(next, id))
    }

    /** Submission must not await withdrawal of this session's plugin. */
    fun activate(cancelActive: Boolean = false): ProfileCommandHandle {
        owner.requireOpen()
        val current = state.value
        check(!current.busy && !current.dirty) { "Profile has unsaved work" }
        val preview = requireNotNull(current.preview) { "Preview Profile before activation" }
        check(preview.packagesVerified && preview.diagnostics.isEmpty() && preview.revision == current.documentRevision) {
            "Profile preview is not deployable"
        }
        check(client.state.value.phase == ProfileManagementPhase.Ready ||
            client.state.value.phase == ProfileManagementPhase.RecoveryRequired) { "Profile host is not ready" }
        return client.submit(ProfileCommand.Activate(ProfileActivationRequest(requireNotNull(current.target), preview.revision),
            cancelActive)).also { mutableState.value = current.copy(command = it.state.value, failure = null) }
    }

    suspend fun observe(handle: ProfileCommandHandle) = operation {
        handle.state.first { status ->
            mutableState.value = state.value.copy(command = status)
            status.phase != ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase.Queued &&
                status.phase != ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase.Running
        }
        val result = handle.await()
        mutableState.value = state.value.copy(command = result)
        // Refresh is explicit: successful activation may withdraw the submitting session.
    }

    suspend fun close() {
        pendingLeave = null
        owner.close()
    }

    private suspend fun load(catalogue: ProfileCatalogue, target: ProfileTarget) {
        val preview = client.preview(target)
        val history = client.history(target.profileId)
        val modules = client.modules()
        check(preview.revision == catalogue.revision) { "Profile repository changed; reload" }
        val document = encode(preview.definition)
        mutableState.value = state.value.copy(catalogue = catalogue, target = target,
            document = document, savedDocument = document, documentRevision = catalogue.revision, dirty = false,
            preview = preview, history = history, modules = modules, failure = null)
    }

    private fun defaultTarget(catalogue: ProfileCatalogue, id: String): ProfileTarget =
        ProfileTarget(id, if (catalogue.profiles.single { it.id == id }.generation == null) ProfileSource.Draft else ProfileSource.Committed)

    private fun encode(definition: ProfileDefinition) = json.encodeToString(ProfileDefinition.serializer(), definition)

    private suspend fun operation(block: suspend () -> Unit) = owner.runIfOpen {
        mutex.withLock { runOperation(block) }
    }

    private suspend fun fileOperation(block: suspend () -> Unit) = owner.runIfOpen {
        if (!mutex.tryLock()) return@runIfOpen
        try { runOperation(block) } finally { mutex.unlock() }
    }

    private suspend fun runOperation(block: suspend () -> Unit) {
        mutableState.value = state.value.copy(busy = true, failure = null, exchange = null)
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableState.value = state.value.copy(failure = ProfileUiFailure.OperationFailed) }
        finally { mutableState.value = state.value.copy(busy = false) }
    }
}
