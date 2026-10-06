package ai.meteor.kcode.plugin.managementui

import ai.meteor.kcode.plugin.api.PluginOperationOwner
import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleImport
import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileCommand
import ai.meteor.kcode.plugin.api.profiles.ProfileCommandPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementClient
import ai.meteor.kcode.plugin.api.profiles.ProfileManagementPhase
import ai.meteor.kcode.plugin.api.profiles.ProfileModuleSummary
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
import ai.meteor.kcode.plugin.api.profiles.ProfilePreview
import ai.meteor.kcode.plugin.api.profiles.ProfileSource
import ai.meteor.kcode.plugin.api.profiles.ProfileSummary
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal data class ProfilePluginManagerState(
    val catalogue: ProfileCatalogue? = null,
    val target: ProfileTarget? = null,
    val preview: ProfilePreview? = null,
    val modules: List<ProfileModuleSummary> = emptyList(),
    val modulesFailure: String? = null,
    val busy: Boolean = false,
    val failure: String? = null,
    val commandPhase: ProfileCommandPhase? = null,
)

internal class ProfilePluginManagerSession(
    private val client: ProfileManagementClient,
    private val activeProfileOnly: Boolean = false,
) {
    private val owner = PluginOperationOwner("Profile plugin management UI")
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ProfilePluginManagerState())
    val state = mutableState.asStateFlow()

    suspend fun refresh() = operation {
        val catalogue = client.catalogue()
        val modules = moduleSnapshot()
        val currentId = state.value.target?.profileId?.takeIf { id -> catalogue.profiles.any { it.id == id } }
        val profileId = if (activeProfileOnly) {
            catalogue.activeProfileId?.takeIf { id -> catalogue.profiles.any { it.id == id } }
        } else {
            currentId ?: catalogue.activeProfileId ?: catalogue.selectedProfileId
                ?: catalogue.profiles.firstOrNull()?.id
        }
        if (profileId == null) {
            mutableState.value = ProfilePluginManagerState(
                catalogue = catalogue,
                modules = modules.modules,
                modulesFailure = modules.failure,
            )
        } else {
            load(catalogue, defaultTarget(catalogue, profileId), modules)
        }
    }

    suspend fun selectProfile(id: String) = operation {
        val catalogue = client.catalogue()
        require(catalogue.profiles.any { it.id == id }) { "Profile '$id' is unavailable" }
        load(catalogue, defaultTarget(catalogue, id), moduleSnapshot())
    }

    suspend fun createFromTemplate(template: ProfileDefinition, id: String) = operation {
        val catalogue = client.catalogue()
        val definition = template.copy(
            id = id,
            displayName = id,
            dataScope = ProfileDataScope(workspace = "profile"),
        ).also(ProfileDefinition::validate)
        val next = client.writeDraft(ProfileDraftWrite(definition, catalogue.revision, createOnly = true))
        load(next, ProfileTarget(id, ProfileSource.Draft), moduleSnapshot())
    }

    suspend fun importBundles(
        id: String,
        displayName: String,
        bundles: List<ProfileBundleArchiveReference>,
    ) = operation {
        require(bundles.isNotEmpty()) { "Select at least one Bundle archive" }
        val catalogue = client.catalogue()
        val next = client.importBundles(ProfileBundleImport(
            archives = bundles,
            id = id,
            expectedRevision = catalogue.revision,
            displayName = displayName.ifBlank { id },
        ))
        load(next, ProfileTarget(id, ProfileSource.Draft), moduleSnapshot())
    }

    suspend fun add(moduleId: String, entryId: String) = append(ProfileOperation.Insert(
        listOf(ProfileEntry(id = entryId, packageId = moduleId)),
    ))

    suspend fun configure(entryId: String, config: JsonElement, configurationKind: String) = append(
        ProfileOperation.Configure(entryId, config, configurationKind),
    )

    suspend fun setEnabled(entryId: String, enabled: Boolean) = append(
        if (enabled) ProfileOperation.Enable(entryId) else ProfileOperation.Disable(entryId),
    )

    suspend fun remove(entryId: String) = append(ProfileOperation.Remove(entryId))

    suspend fun activate(cancelActive: Boolean) = operation {
        val current = state.value
        val catalogue = requireNotNull(current.catalogue) { "Refresh the Profile catalogue first" }
        val target = requireNotNull(current.target) { "Select a Profile first" }
        val preview = requireNotNull(current.preview) { "Preview the Profile before activation" }
        check(preview.revision == catalogue.revision && preview.packagesVerified && preview.diagnostics.isEmpty()) {
            "Profile preview is stale or has diagnostics"
        }
        val phase = client.state.value.phase
        check(phase == ProfileManagementPhase.Ready || phase == ProfileManagementPhase.RecoveryRequired) {
            "Profile host is not ready to activate a composition"
        }
        val handle = client.submit(ProfileCommand.Activate(
            ProfileActivationRequest(target, catalogue.revision),
            cancelActive,
        ))
        mutableState.value = current.copy(commandPhase = ProfileCommandPhase.Queued)
        val result = handle.await()
        mutableState.value = state.value.copy(commandPhase = result.phase, failure = result.failure)
        if (result.phase == ProfileCommandPhase.Succeeded) {
            val next = client.catalogue()
            load(next, defaultTarget(next, target.profileId), moduleSnapshot(current.modules))
        } else {
            error(result.failure ?: "Profile activation did not succeed")
        }
    }

    suspend fun close() = owner.close()

    private suspend fun append(change: ProfileOperation) = operation {
        val current = state.value
        val catalogue = requireNotNull(current.catalogue) { "Refresh the Profile catalogue first" }
        val target = requireNotNull(current.target) { "Select a Profile first" }
        check(target.source != ProfileSource.History) { "Historical Profiles are read-only" }
        val definition = requireNotNull(current.preview).definition.copy(
            patches = current.preview.definition.patches + detach(change),
        )
        val next = client.writeDraft(ProfileDraftWrite(definition, catalogue.revision))
        load(next, ProfileTarget(target.profileId, ProfileSource.Draft), ModuleSnapshot(current.modules, current.modulesFailure))
    }

    private suspend fun load(
        catalogue: ProfileCatalogue,
        target: ProfileTarget,
        modules: ModuleSnapshot,
    ) {
        val preview = client.preview(target)
        check(preview.revision == catalogue.revision) { "Profile repository changed; refresh and retry" }
        mutableState.value = ProfilePluginManagerState(
            catalogue = catalogue,
            target = target,
            preview = preview,
            modules = modules.modules,
            modulesFailure = modules.failure,
        )
    }

    private suspend fun moduleSnapshot(fallback: List<ProfileModuleSummary> = state.value.modules): ModuleSnapshot {
        if (client.state.value.phase == ProfileManagementPhase.RecoveryRequired) return ModuleSnapshot(fallback, null)
        return try {
            ModuleSnapshot(client.modules(), null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ModuleSnapshot(fallback, failure.message ?: failure.toString())
        }
    }

    private suspend fun defaultTarget(catalogue: ProfileCatalogue, id: String): ProfileTarget {
        val profile = catalogue.profiles.single { it.id == id }
        return ProfileTarget(
            id,
            if (hasPendingDraft(profile)) ProfileSource.Draft else ProfileSource.Committed,
        )
    }

    private suspend fun hasPendingDraft(profile: ProfileSummary): Boolean {
        if (profile.generation == null) return true
        if (!profile.hasDraft) return false
        if (!activeProfileOnly) return true
        val draft = client.draft(profile.id) ?: return false
        val committed = client.history(profile.id).lastOrNull { it.generation == profile.generation }
        return committed?.definition != draft
    }

    private fun detach(operation: ProfileOperation): ProfileOperation = Json.decodeFromString(
        ProfileOperation.serializer(),
        Json.encodeToString(ProfileOperation.serializer(), operation),
    )

    private suspend fun operation(block: suspend () -> Unit) = owner.runIfOpen {
        if (!mutex.tryLock()) return@runIfOpen
        mutableState.value = state.value.copy(busy = true, failure = null)
        try {
            val phase = client.state.first {
                it.phase != ProfileManagementPhase.Starting && it.phase != ProfileManagementPhase.Transitioning
            }.phase
            check(phase != ProfileManagementPhase.Closed) { "Profile management is closed" }
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = state.value.copy(failure = failure.message ?: failure.toString())
        } finally {
            mutableState.value = state.value.copy(busy = false)
            mutex.unlock()
        }
    }
}

private data class ModuleSnapshot(
    val modules: List<ProfileModuleSummary>,
    val failure: String?,
)

internal fun flattenPluginEntries(entries: List<ProfileEntry>): List<ProfileEntry> = buildList {
    fun addAll(current: List<ProfileEntry>) {
        current.forEach { entry ->
            val children = entry.children
            if (children == null) add(entry) else addAll(children)
        }
    }
    addAll(entries)
}
