package ai.meteor.kcode.plugin.api.profiles

import kotlinx.coroutines.flow.StateFlow
import org.cordis.Context
import org.cordis.Service
import org.cordis.ServiceKey

enum class ProfileManagementPhase { Starting, Ready, Transitioning, RecoveryRequired, Closed }

data class ProfileManagementState(
    val phase: ProfileManagementPhase,
    val activeProfileId: String? = null,
    val failure: String? = null,
)

enum class ProfileModuleSource { Host, Package }

data class ProfileModuleSummary(
    val id: String,
    val version: String,
    val source: ProfileModuleSource,
    val instances: List<String>,
)

sealed interface ProfileCommand {
    data class Activate(val request: ProfileActivationRequest, val cancelActive: Boolean = false) : ProfileCommand
    data class Edit(val edit: ProfileCompositionEdit) : ProfileCommand
    data class SelectModule(val packageId: String, val moduleId: String, val expected: ProfileCompositionState) : ProfileCommand
}

enum class ProfileCommandPhase { Queued, Running, Succeeded, Failed, Cancelled }

data class ProfileCommandStatus(
    val id: Long,
    val phase: ProfileCommandPhase,
    val result: ProfileCompositionState? = null,
    val failure: String? = null,
)

/** Host-owned work survives its submitting plugin. Cancelling an observer does not cancel work. */
interface ProfileCommandHandle {
    val id: Long
    val state: StateFlow<ProfileCommandStatus>
    suspend fun await(): ProfileCommandStatus
    /** Abort before publication; a published command retains its successful committed result. */
    fun cancel()
}

interface ProfileManagementClient {
    val state: StateFlow<ProfileManagementState>
    val commands: StateFlow<List<ProfileCommandStatus>>
    suspend fun catalogue(): ProfileCatalogue
    suspend fun draft(id: String): ProfileDefinition?
    suspend fun writeDraft(write: ProfileDraftWrite): ProfileCatalogue
    suspend fun clone(request: ProfileCloneRequest): ProfileCatalogue
    suspend fun delete(id: String, expectedRevision: Long): ProfileCatalogue
    suspend fun preview(target: ProfileTarget): ProfilePreview
    suspend fun history(id: String): List<ProfileCompositionState>
    suspend fun modules(): List<ProfileModuleSummary>
    /** Metadata-only publication; activation requires a separate explicit command. */
    suspend fun importPortable(request: ProfilePortableImport): ProfileCatalogue =
        error("This client does not support portable Profile import")
    /** Host feature policies review opaque configuration; callers cannot bypass that review. */
    suspend fun exportPortable(request: ProfilePortableExport): String =
        error("This client does not support portable Profile export")
    /** Acceptance is synchronous; this does not await withdrawal of the submitting plugin. */
    fun submit(command: ProfileCommand): ProfileCommandHandle
}

/** Neutral management bridge; product UI consumes this contract through Cordis injection. */
class KcodeProfiles(ctx: Context, val client: ProfileManagementClient) : Service<Unit>(ctx, Key) {
    companion object {
        val Key = ServiceKey<KcodeProfiles>("profiles")
    }
}
