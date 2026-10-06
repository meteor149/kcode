package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfilePortableExport

/** Native verified transport; metadata and activation remain owned by management/the host. */
interface ProfileArchiveTransport {
    suspend fun prepareArchive(input: ProfileArchiveReference, id: String, displayName: String): PortableProfileDocument
    suspend fun exportArchive(management: ProfileManagement, request: ProfilePortableExport, consume: suspend (ProfileArchiveReference) -> Unit)
}
