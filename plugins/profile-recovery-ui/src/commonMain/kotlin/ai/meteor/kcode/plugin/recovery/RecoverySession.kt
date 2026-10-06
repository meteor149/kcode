package ai.meteor.kcode.plugin.recovery

import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json

internal data class RecoveryState(
    val catalogue: ProfileCatalogue? = null,
    val target: ProfileTarget? = null,
    val document: String = "",
    val savedDocument: String = "",
    val revision: Long? = null,
    val busy: Boolean = false,
    val failure: String? = null,
) {
    val dirty: Boolean get() = document != savedDocument
}

/** Host metadata remains usable without product services or a loadable module tree. */
internal class RecoverySession(private val client: ProfileManagementClient) {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val mutableState = MutableStateFlow(RecoveryState())
    val state = mutableState.asStateFlow()

    fun edit(document: String) {
        if (!state.value.busy && state.value.target != null) {
            mutableState.value = state.value.copy(document = document, failure = null)
        }
    }

    fun discard() {
        if (!state.value.busy) mutableState.value = state.value.copy(document = state.value.savedDocument, failure = null)
    }

    suspend fun refresh() = operation {
        val catalogue = client.catalogue()
        if (state.value.dirty) {
            // Updating catalogue metadata cannot authorize overwriting intervening edits.
            mutableState.value = state.value.copy(catalogue = catalogue)
        } else {
            val target = state.value.target?.takeIf { value -> catalogue.profiles.any { it.id == value.profileId } }
                ?: catalogue.profiles.firstOrNull { it.id == catalogue.selectedProfileId }
                    ?.let { ProfileTarget(it.id, if (it.generation == null) ProfileSource.Draft else ProfileSource.Committed) }
                ?: catalogue.profiles.firstOrNull()
                    ?.let { ProfileTarget(it.id, if (it.generation == null) ProfileSource.Draft else ProfileSource.Committed) }
            if (target == null) mutableState.value = state.value.copy(catalogue = catalogue, target = null)
            else load(catalogue, target)
        }
    }

    suspend fun select(target: ProfileTarget) = operation {
        check(!state.value.dirty) { "unsaved" }
        load(client.catalogue(), target)
    }

    private suspend fun load(catalogue: ProfileCatalogue, target: ProfileTarget) {
        target.validate()
        val definition = when (target.source) {
            ProfileSource.Draft -> requireNotNull(client.draft(target.profileId)) { "missing" }
            ProfileSource.Committed -> {
                val generation = requireNotNull(catalogue.profiles.single { it.id == target.profileId }.generation) { "missing" }
                client.history(target.profileId).single { it.generation == generation }.definition
            }
            ProfileSource.History -> client.history(target.profileId).single { it.generation == target.generation }.definition
        }
        check(client.catalogue().revision == catalogue.revision) { "changed" }
        val document = json.encodeToString(ProfileDefinition.serializer(), definition)
        mutableState.value = state.value.copy(catalogue = catalogue, target = target, document = document,
            savedDocument = document, revision = catalogue.revision)
    }

    suspend fun save() = operation { saveDocument() }

    private suspend fun saveDocument() {
        val current = state.value
        val definition = json.decodeFromString(ProfileDefinition.serializer(), current.document)
        definition.validate()
        require(definition.id == current.target?.profileId) { "identity" }
        val catalogue = client.writeDraft(ProfileDraftWrite(definition, requireNotNull(current.revision)))
        val document = json.encodeToString(ProfileDefinition.serializer(), definition)
        mutableState.value = current.copy(catalogue = catalogue, target = ProfileTarget(definition.id, ProfileSource.Draft),
            document = document, savedDocument = document, revision = catalogue.revision)
    }

    suspend fun activate(saveFirst: Boolean = false) = operation {
        if (saveFirst) saveDocument() else check(!state.value.dirty) { "unsaved" }
        val current = state.value
        val handle = client.submit(ProfileCommand.Activate(ProfileActivationRequest(
            requireNotNull(current.target), requireNotNull(current.revision),
        )))
        // Cancellation of this observer must not cancel the host-owned accepted command.
        val result = handle.await()
        if (result.phase != ProfileCommandPhase.Succeeded) {
            mutableState.value = state.value.copy(failure = result.failure ?: "activation_failed")
        }
    }

    private suspend fun operation(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        mutableState.value = state.value.copy(busy = true, failure = null)
        try {
            val phase = client.state.first { it.phase != ProfileManagementPhase.Starting && it.phase != ProfileManagementPhase.Transitioning }.phase
            check(phase != ProfileManagementPhase.Closed) { "closed" }
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = state.value.copy(failure = error.message ?: error.toString())
        } finally {
            mutableState.value = state.value.copy(busy = false)
            mutex.unlock()
        }
    }
}
