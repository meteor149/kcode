package ai.meteor.kcode.plugin.profileui

import androidx.compose.runtime.Composable
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference
import ai.meteor.kcode.plugin.api.profiles.ProfileArchiveReference

/** A null read/false write is native picker cancellation, not a Profile command. */
internal data class ProfileBundleFile(val archive: ProfileBundleArchiveReference, val name: String)

internal interface ProfileDocumentFiles {
    suspend fun read(title: String): String?
    suspend fun write(title: String, name: String, document: String): Boolean
    /** Own staged inputs until the consuming command completes, then remove them. */
    suspend fun readBundles(title: String, consume: suspend (List<ProfileBundleFile>) -> Unit): Boolean =
        error("Bundle file selection is unavailable")
    suspend fun readArchive(title: String, consume: suspend (ProfileArchiveReference) -> Unit): Boolean =
        error("Profile archive selection is unavailable")
    suspend fun writeArchive(title: String, name: String, archive: ProfileArchiveReference): Boolean =
        error("Profile archive saving is unavailable")
}

internal const val ProfileDocumentByteLimit = 2 * 1024 * 1024

@Composable
internal expect fun rememberProfileDocumentFiles(): ProfileDocumentFiles
