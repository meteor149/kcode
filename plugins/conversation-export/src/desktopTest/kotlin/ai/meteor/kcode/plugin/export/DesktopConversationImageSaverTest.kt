package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.export.ImageSaveResult
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

class DesktopConversationImageSaverTest {
    @Test
    fun savesActualPngAndRejectsCallsAfterRelease() = runTest {
        val directory = Files.createTempDirectory("kcode-export-native-")
        val saver = DesktopConversationImageSaver { name ->
            assertEquals("requested.png", name)
            directory.resolve("selected").toFile()
        }
        try {
            val target = directory.resolve("selected.png")
            assertEquals(ImageSaveResult.Saved(target.toString()), saver.save(pixels(), "requested.png"))
            val decoded = ImageIO.read(target.toFile())
            assertEquals(2, decoded.width)
            assertEquals(3, decoded.height)
            assertEquals(0xffff0000.toInt(), decoded.getRGB(0, 0))
            assertEquals(listOf("selected.png"), directory.toFile().listFiles()!!.map { it.name })
            saver.close()
            assertFailsWith<IllegalStateException> { saver.share(pixels(), "fixture.png") }
        } finally { saver.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun cancelledPixelConversionDoesNotOverwriteSelectedFile() = runTest {
        val directory = Files.createTempDirectory("kcode-export-cancel-")
        val target = Files.writeString(directory.resolve("selected.png"), "original")
        val saver = DesktopConversationImageSaver { target.toFile() }
        try {
            assertFailsWith<CancellationException> { saver.save(pixels(cancel = true), "requested.png") }
            assertEquals("original", Files.readString(target))
            assertEquals(listOf("selected.png"), directory.toFile().listFiles()!!.map { it.name })
        } finally { saver.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun cancellationPropagatesThroughFileSelection() = runTest {
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val saver = DesktopConversationImageSaver {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cleaned = true }
        }
        val saving = backgroundScope.async { saver.save(pixels(), "fixture.png") }
        entered.await()
        saving.cancel()
        saving.join()
        assertTrue(saving.isCancelled)
        assertTrue(cleaned)
        saver.close()
    }

    private fun pixels(cancel: Boolean = false) = object : ImageBitmap {
        override val width = 2
        override val height = 3
        override val colorSpace = ColorSpaces.Srgb
        override val hasAlpha = true
        override val config = ImageBitmapConfig.Argb8888
        override fun prepareToDraw() = Unit
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {
            if (cancel) throw CancellationException("pixel conversion cancelled")
            for (y in 0 until height) for (x in 0 until width) buffer[bufferOffset + y * stride + x] = 0xffff0000.toInt()
        }
    }
}
