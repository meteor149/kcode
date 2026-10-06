package ai.meteor.kcode.plugin.profileui

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileBundleFilesTest {
    @Test
    fun stagingPreservesBytesAndOrderAndCleansUpWhenConsumerFails(): Unit = runBlocking {
        var staged = emptyList<Path>()
        assertFailsWith<IllegalStateException> {
            stageProfileBundles(listOf({ ByteArrayInputStream("first".toByteArray()) }, { ByteArrayInputStream("second".toByteArray()) })) { archives ->
                staged = archives.map { Path.of(it.archivePath) }
                assertEquals(listOf("first", "second"), staged.map(Files::readString))
                assertTrue(archives.all { it.sha256.matches(Regex("[a-f0-9]{64}")) })
                error("consumer refused")
            }
        }
        assertEquals(2, staged.size)
        assertTrue(staged.none { Files.exists(it) })
        assertFalse(Files.exists(staged.first().parent))
        var consumed = false
        assertFailsWith<IllegalArgumentException> {
            stageProfileBundles(listOf({ ByteArrayInputStream(ByteArray(3)) }), byteLimit = 2) { consumed = true }
        }
        assertFalse(consumed)
        assertFailsWith<CancellationException> {
            stageProfileBundles(listOf({ ByteArrayInputStream(ByteArray(1)) })) { throw CancellationException("cancel") }
        }
    }
}
