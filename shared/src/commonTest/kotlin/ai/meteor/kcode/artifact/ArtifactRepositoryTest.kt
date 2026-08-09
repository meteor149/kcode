package ai.meteor.kcode.artifact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class ArtifactRepositoryTest {
    @Test
    fun missingManifestMeansNoArtifacts() = runTest {
        assertEquals(emptyList(), FileArtifactRepository(MemoryStore()).list())
    }

    @Test
    fun readsValidatedWebAppArtifact() = runTest {
        val store = MemoryStore(
            ArtifactManifestPath to """
                {
                  "version": 1,
                  "artifacts": [{
                    "id": "weather",
                    "name": "Weather App",
                    "type": "web_app",
                    "directory": "weather",
                    "entry_point": "index.html",
                    "description": "A local forecast"
                  }]
                }
            """.trimIndent(),
            "$ArtifactResourcesRoot/weather/index.html" to "<html></html>",
        )

        val artifact = FileArtifactRepository(store).list().single()

        assertEquals("weather", artifact.id)
        assertEquals(ArtifactType.WebApp, artifact.type)
        assertEquals("/workspace/artifacts/resources/weather/index.html", artifact.entryPath)
    }

    @Test
    fun rejectsTraversalDuplicateIdsUnsupportedTypesAndMissingEntries() = runTest {
        suspend fun fails(manifest: String) {
            assertFailsWith<IllegalArgumentException> {
                FileArtifactRepository(MemoryStore(ArtifactManifestPath to manifest)).list()
            }
        }

        fails(manifest(directory = "../escape"))
        fails(manifest(type = "native_app"))
        fails(manifest(entryPoint = "missing.html"))
        fails(
            """{"version":1,"artifacts":[
                {"id":"same","name":"One","type":"web_app","directory":"one"},
                {"id":"same","name":"Two","type":"web_app","directory":"two"}
            ]}""",
        )
    }

    private fun manifest(
        directory: String = "app",
        type: String = "web_app",
        entryPoint: String = "index.html",
    ): String = """
        {"version":1,"artifacts":[{
          "id":"app","name":"App","type":"$type","directory":"$directory","entry_point":"$entryPoint"
        }]}
    """.trimIndent()

    private class MemoryStore(vararg initial: Pair<String, String>) : ArtifactFileStore {
        private val files = initial.toMap()

        override suspend fun readText(path: String): String? = files[path]

        override suspend fun exists(path: String): Boolean = path in files
    }
}
