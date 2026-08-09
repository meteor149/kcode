package ai.meteor.kcode

import ai.meteor.kcode.artifact.Artifact
import ai.meteor.kcode.artifact.ArtifactType
import ai.meteor.kcode.artifact.MutableArtifactRepository
import ai.meteor.kcode.artifact.SaveWebArtifactRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class ArtifactToolsTest {
    @Test
    fun requiresExplicitConfirmation() = runTest {
        val repository = RecordingRepository()
        val tool = SaveWebArtifactTool(repository)

        assertFailsWith<IllegalArgumentException> {
            tool.execute(args(userConfirmed = false))
        }
        assertEquals(null, repository.saved)
    }

    @Test
    fun savesConfirmedWebArtifact() = runTest {
        val repository = RecordingRepository()
        val output = SaveWebArtifactTool(repository).execute(args(userConfirmed = true))

        assertEquals("demo", repository.saved?.id)
        assertEquals("/workspace/demo", repository.saved?.sourceDirectory)
        kotlin.test.assertTrue(output.contains("Saved artifact demo"))
    }

    private fun args(userConfirmed: Boolean) = SaveWebArtifactTool.Args(
        id = "demo",
        name = "Demo",
        sourceDirectory = "/workspace/demo",
        userConfirmed = userConfirmed,
    )

    private class RecordingRepository : MutableArtifactRepository {
        var saved: SaveWebArtifactRequest? = null

        override suspend fun list(): List<Artifact> = emptyList()

        override suspend fun saveWebApp(request: SaveWebArtifactRequest): Artifact {
            saved = request
            return Artifact(request.id, request.name, ArtifactType.WebApp, request.id, request.entryPoint, request.description)
        }
    }
}
