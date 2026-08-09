package ai.meteor.kcode.artifact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
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

    @Test
    fun savesAWebAppByCopyingResourcesAndUpdatingManifest() = runTest {
        val store = MutableMemoryStore(
            "/workspace/draft/index.html" to "<html>draft</html>".encodeToByteArray(),
            "/workspace/draft/assets/app.js" to "console.log('ok')".encodeToByteArray(),
        )
        val repository = FileArtifactRepository(store)

        val artifact = repository.saveWebApp(
            SaveWebArtifactRequest(
                id = "saved-app",
                name = "Saved App",
                sourceDirectory = "/workspace/draft",
                description = "Created in chat",
            ),
        )

        assertEquals("/workspace/artifacts/resources/saved-app/index.html", artifact.entryPath)
        assertContentEquals(
            "<html>draft</html>".encodeToByteArray(),
            store.readBytes(artifact.entryPath),
        )
        assertEquals(listOf("saved-app"), repository.list().map { it.id })
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

    private class MutableMemoryStore(vararg initial: Pair<String, ByteArray>) : MutableArtifactFileStore {
        private val files = initial.toMap().toMutableMap()

        override suspend fun readText(path: String): String? = files[path]?.decodeToString()
        override suspend fun exists(path: String): Boolean = path in files || files.keys.any { it.startsWith("${path.trimEnd('/')}/") }
        override suspend fun readBytes(path: String): ByteArray? = files[path]
        override suspend fun writeBytesAtomically(path: String, contents: ByteArray) {
            files[path] = contents
        }

        override suspend fun list(path: String): List<ArtifactFileEntry> {
            val prefix = path.trimEnd('/') + "/"
            val entries = linkedMapOf<String, ArtifactFileEntry>()
            files.forEach { (file, contents) ->
                if (!file.startsWith(prefix)) return@forEach
                val remainder = file.removePrefix(prefix)
                val childName = remainder.substringBefore('/')
                val childPath = prefix + childName
                val directory = '/' in remainder
                entries[childPath] = ArtifactFileEntry(
                    path = childPath,
                    directory = directory,
                    size = if (directory) 0L else contents.size.toLong(),
                )
            }
            require(entries.isNotEmpty()) { "Directory does not exist: $path" }
            return entries.values.toList()
        }

        override suspend fun deleteTree(path: String) {
            val prefix = path.trimEnd('/') + "/"
            files.keys.filter { it == path || it.startsWith(prefix) }.toList().forEach(files::remove)
        }

        override suspend fun moveTree(source: String, target: String) {
            val prefix = source.trimEnd('/') + "/"
            val moving = files.filterKeys { it.startsWith(prefix) }
            require(moving.isNotEmpty())
            moving.forEach { (path, contents) -> files[target.trimEnd('/') + "/" + path.removePrefix(prefix)] = contents }
            moving.keys.forEach(files::remove)
        }
    }
}
