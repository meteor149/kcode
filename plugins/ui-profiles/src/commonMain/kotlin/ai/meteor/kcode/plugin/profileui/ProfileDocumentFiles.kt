package ai.meteor.kcode.plugin.profileui

import androidx.compose.runtime.Composable

/** A null read/false write is native picker cancellation, not a Profile command. */
internal interface ProfileDocumentFiles {
    suspend fun read(title: String): String?
    suspend fun write(title: String, name: String, document: String): Boolean
}

internal const val ProfileDocumentByteLimit = 2 * 1024 * 1024

@Composable
internal expect fun rememberProfileDocumentFiles(): ProfileDocumentFiles
