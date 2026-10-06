package ai.meteor.kcode.plugin.managementui

import ai.meteor.kcode.plugin.api.profiles.ProfileBundleArchiveReference
import androidx.compose.runtime.Composable

internal data class ProfileManagerBundleFile(
    val reference: ProfileBundleArchiveReference,
    val name: String,
)

/** Selected files and staged temporary archives remain owned until consume returns. */
internal interface ProfileManagerBundleFiles {
    suspend fun withBundles(
        title: String,
        singlePlugin: Boolean = false,
        consume: suspend (List<ProfileManagerBundleFile>) -> Unit,
    ): Boolean
}

@Composable
internal expect fun rememberProfileManagerBundleFiles(): ProfileManagerBundleFiles
