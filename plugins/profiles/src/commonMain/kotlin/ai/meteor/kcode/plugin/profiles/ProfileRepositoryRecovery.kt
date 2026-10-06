package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue

/** Native host recovery metadata; deliberately outside the replaceable product SDK. */
enum class ProfileRepositoryRepairMode { RestoreCheckpoint, StartEmpty }

data class ProfileRepositoryRecoveryReview(
    val fingerprint: String,
    val failure: String,
    val checkpoint: ProfileCatalogue?,
    val checkpointFailure: String?,
)

data class ProfileRepositoryRepairRequest(
    val expectedFingerprint: String,
    val mode: ProfileRepositoryRepairMode,
)

data class ProfileRepositoryRepairResult(
    val catalogue: ProfileCatalogue,
    val evidenceId: String,
)

interface ProfileRepositoryRecovery {
    /** Healthy repositories return null; inspecting damage never rewrites authority. */
    suspend fun inspectRecovery(): ProfileRepositoryRecoveryReview?
    /** Compare inspected bytes, preserve evidence, then atomically publish metadata only. */
    suspend fun repair(request: ProfileRepositoryRepairRequest): ProfileRepositoryRepairResult
}
