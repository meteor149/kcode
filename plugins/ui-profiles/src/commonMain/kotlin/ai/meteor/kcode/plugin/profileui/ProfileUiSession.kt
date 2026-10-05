package ai.meteor.kcode.plugin.profileui

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
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
import ai.meteor.kcode.plugin.api.profiles.ProfilePreview
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

enum class ProfileUiFailure { InvalidDocument, OperationFailed }

data class ProfileUiState(
    val catalogue: ProfileCatalogue? = null,
    val target: ProfileTarget? = null,
    val document: String = "",
    val documentRevision: Long? = null,
    val dirty: Boolean = false,
    val busy: Boolean = false,
    val preview: ProfilePreview? = null,
    val history: List<ProfileCompositionState> = emptyList(),
    val modules: List<ProfileModuleSummary> = emptyList(),
    val command: ProfileCommandStatus? = null,
    val failure: ProfileUiFailure? = null,
)

/** Presentation owns queries/observers; the native host owns accepted composition commands. */
class ProfileUiSession(private val client: ProfileManagementClient) {
    private val owner = PluginOperationOwner("profile-ui")
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }
    private val mutableState = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()

    fun edit(document: String) {
        owner.requireOpen()
        check(!state.value.busy && state.value.target != null) { "Profile editor is not available" }
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
                document = "", documentRevision = catalogue.revision, preview = null, history = emptyList())
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

    suspend fun save() = operation {
        val current = state.value
        val definition = try {
            json.decodeFromString(ProfileDefinition.serializer(), current.document).also {
                it.validate()
                require(it.id == current.target?.profileId) { "Editor cannot change Profile identity" }
            }
        } catch (error: IllegalArgumentException) {
            mutableState.value = current.copy(failure = ProfileUiFailure.InvalidDocument)
            return@operation
        }
        val next = client.writeDraft(ProfileDraftWrite(definition, requireNotNull(current.documentRevision)))
        mutableState.value = current.copy(catalogue = next, target = ProfileTarget(definition.id, ProfileSource.Draft),
            document = encode(definition), documentRevision = next.revision, dirty = false, preview = null)
    }

    suspend fun preview() = operation {
        val current = state.value
        check(!current.dirty) { "Save Profile before preview" }
        val result = client.preview(requireNotNull(current.target))
        check(result.revision == current.documentRevision) { "Profile changed; reload before preview" }
        mutableState.value = current.copy(preview = result)
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

    suspend fun close() { owner.close() }

    private suspend fun load(catalogue: ProfileCatalogue, target: ProfileTarget) {
        val preview = client.preview(target)
        val history = client.history(target.profileId)
        val modules = client.modules()
        check(preview.revision == catalogue.revision) { "Profile repository changed; reload" }
        mutableState.value = state.value.copy(catalogue = catalogue, target = target,
            document = encode(preview.definition), documentRevision = catalogue.revision, dirty = false,
            preview = preview, history = history, modules = modules, failure = null)
    }

    private fun defaultTarget(catalogue: ProfileCatalogue, id: String): ProfileTarget =
        ProfileTarget(id, if (catalogue.profiles.single { it.id == id }.generation == null) ProfileSource.Draft else ProfileSource.Committed)

    private fun encode(definition: ProfileDefinition) = json.encodeToString(ProfileDefinition.serializer(), definition)

    private suspend fun operation(block: suspend () -> Unit) = owner.run {
        mutex.withLock {
            mutableState.value = state.value.copy(busy = true, failure = null)
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = state.value.copy(failure = ProfileUiFailure.OperationFailed) }
            finally { mutableState.value = state.value.copy(busy = false) }
        }
    }
}
