package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference

data class PreparedProfilePluginImport(val packageId: String, val lock: ProfileLock)

/** Verification stages immutable code only. No provider effects run before explicit activation. */
fun interface ProfilePluginImportPreparation {
    suspend fun prepare(archive: ProfileArchiveReference, existing: ProfileLock): PreparedProfilePluginImport
}
