package ai.meteor.kcode.plugin.profileui

import androidx.compose.runtime.Composable
import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference

/** A null read/false write is native picker cancellation, not a Profile command. */
internal interface ProfileDocumentFiles {
    suspend fun read(title: String): String?
    suspend fun write(title: String, name: String, document: String): Boolean
    /** Own staged inputs until the consuming command completes, then remove them. */
    suspend fun readBundles(title: String, consume: suspend (List<ProfileBundleArchiveReference>) -> Unit): Boolean =
        error("Bundle file selection is unavailable")
}

internal const val ProfileDocumentByteLimit = 2 * 1024 * 1024

@Composable
internal expect fun rememberProfileDocumentFiles(): ProfileDocumentFiles
