package ai.meteor.kcode.plugin.artifacts

import ai.meteor.kcode.artifact.MutableArtifactFileStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class NativeArtifactFileStoreTest {
    @Test
    fun nativeResourceWritesMovesDeletesAndRejectsAccessAfterRelease() = runTest {
        val directory = Files.createTempDirectory("native-artifact-store")
        val resource = desktopArtifactFileStoreFactory(directory).create()
        try {
            val store = resource.store as MutableArtifactFileStore
            store.writeBytesAtomically("/workspace/draft/index.html", "native".encodeToByteArray())
            assertEquals("native", store.readText("/workspace/draft/index.html"))
            assertEquals(listOf("/workspace/draft/index.html"), store.list("/workspace/draft").map { it.path })
            store.moveTree("/workspace/draft", "/workspace/moved")
            assertFalse(store.exists("/workspace/draft"))
            assertEquals("native", store.readBytes("/workspace/moved/index.html")!!.decodeToString())
            assertFailsWith<IllegalArgumentException> { store.exists("/workspace/../escape") }
            store.deleteTree("/workspace/moved")
            assertFalse(store.exists("/workspace/moved"))
            assertEquals(emptyList(), directory.toFile().listFiles()!!.toList())
            resource.close()
            resource.close()
            assertFailsWith<IllegalStateException> { store.exists("/workspace/moved") }
        } finally { resource.close(); directory.toFile().deleteRecursively() }
    }
    @Test
    fun deletionUnlinksNestedAliasesWithoutDeletingTheirTargetDirectory() = runTest {
        val directory = Files.createTempDirectory("native-artifact-links").toRealPath()
        val root = Files.createDirectory(directory.resolve("workspace"))
        val outside = Files.createDirectory(directory.resolve("outside"))
        val keeper = Files.writeString(outside.resolve("keep.txt"), "retained")
        val resource = desktopArtifactFileStoreFactory(root).create()
        val store = resource.store as MutableArtifactFileStore
        val link = root.resolve("staging/escape")
        try {
            store.writeBytesAtomically("/workspace/staging/index.html", "temporary".encodeToByteArray())
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                val script = directory.resolve("junction.ps1")
                Files.writeString(script, """
                    param([string]${'$'}Link, [string]${'$'}Target)
                    ${'$'}ErrorActionPreference = 'Stop'
                    New-Item -ItemType Junction -Path ${'$'}Link -Value ${'$'}Target | Out-Null
                """.trimIndent())
                val process = ProcessBuilder("powershell.exe", "-NoProfile", "-File", script.toString(),
                    "-Link", link.toString(), "-Target", outside.toString()).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), output)
            } else Files.createSymbolicLink(link, outside)
            assertTrue(Files.isDirectory(link))
            store.deleteTree("/workspace/staging")
            assertEquals("retained", Files.readString(keeper))
            assertFalse(store.exists("/workspace/staging"))
        } finally {
            resource.close()
            Files.deleteIfExists(link)
            check(directory.toRealPath().startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))
            directory.toFile().deleteRecursively()
        }
    }
}
