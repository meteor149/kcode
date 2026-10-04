package ai.meteor.kcode.plugin.artifacts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class AndroidArtifactFileStoreTest {
    @Test(timeout = 60_000)
    fun nativeDeletionUnlinksNestedSymlinksAndClosedResourcesRejectAccess(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "artifact-links-${System.nanoTime()}").apply { mkdirs() }.toPath().toRealPath()
        val root = Files.createDirectory(directory.resolve("workspace"))
        val outside = Files.createDirectory(directory.resolve("outside"))
        val keeper = Files.write(outside.resolve("keep.txt"), "retained".encodeToByteArray())
        val store = AndroidArtifactFileStore(root)
        try {
            store.writeBytesAtomically("/workspace/staging/index.html", "temporary".encodeToByteArray())
            Files.createSymbolicLink(root.resolve("staging/escape"), outside)
            store.deleteTree("/workspace/staging")
            assertEquals("retained", Files.readAllBytes(keeper).decodeToString())
            assertFalse(store.exists("/workspace/staging"))
            store.close()
            assertFailsWith<IllegalStateException> { store.readText("/workspace/staging/index.html") }
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
