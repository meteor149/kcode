package ai.meteor.kcode.plugin.artifacts

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifact.SaveWebArtifactRequest
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class OwnedArtifactRepositoryTest {
    @Test
    fun withdrawalWaitsForMutationCleanupAndRejectsStaleCalls() = runTest {
        val owner = PluginOperationOwner("artifacts")
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = OwnedMutableArtifactRepository(object : MutableArtifactRepository {
            override suspend fun list(): List<Artifact> = emptyList()
            override suspend fun saveWebApp(request: SaveWebArtifactRequest): Artifact {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }, owner)
        val operation = async { repository.saveWebApp(SaveWebArtifactRequest("id", "name", "/workspace/draft")) }
        entered.await()
        val closing = async { owner.close() }
        cleaning.await()
        assertFalse(closing.isCompleted)
        assertFailsWith<IllegalStateException> { repository.list() }
        release.complete(Unit)
        closing.await()
        operation.join()
        assertTrue(operation.isCancelled)
        assertFailsWith<IllegalStateException> { repository.saveWebApp(SaveWebArtifactRequest("id", "name", "/workspace/draft")) }
    }
}
