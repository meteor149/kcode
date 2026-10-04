package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.artifact.ArtifactFileStore

/** Bounded per-mount allocation; the provider joins owned calls before releasing the store. */
fun interface ArtifactFileStoreFactory {
    suspend fun create(): ArtifactFileStoreResource
}

class ArtifactFileStoreResource(
    val store: ArtifactFileStore,
    val close: suspend () -> Unit,
)
