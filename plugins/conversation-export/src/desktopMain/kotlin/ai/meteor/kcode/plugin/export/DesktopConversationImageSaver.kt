package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ImageSaveResult
import ai.meteor.kcode.plugin.api.DesktopPluginHostInputs
import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
import ai.meteor.kcode.plugin.api.ConversationImageSaverResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

fun desktopConversationImageSaverFactory() = ConversationImageSaverFactory {
    val saver = DesktopConversationImageSaver()
    ConversationImageSaverResource(saver, saver::close)
}

fun desktopConversationImageSaverFactory(inputs: DesktopPluginHostInputs) = ConversationImageSaverFactory {
    val saver = DesktopConversationImageSaver { fileName ->
        chooseDesktopExportFile(fileName, inputs::applicationWindow)
    }
    ConversationImageSaverResource(saver, saver::close)
}

internal class DesktopConversationImageSaver(
    private val chooseFile: suspend (String) -> File? = ::chooseDesktopExportFile,
) : ConversationImageSaver {
    @Volatile private var closed = false

    fun close() { closed = true }

    private fun requireOpen() = check(!closed) { "Image saver is closed" }

    override suspend fun share(image: ImageBitmap, fileName: String): ImageSaveResult {
        requireOpen()
        return ImageSaveResult.Unsupported
    }

    override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult {
        requireOpen()
        return try {
            val selected = chooseFile(fileName) ?: return ImageSaveResult.Failed(null)
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) {
                val target = if (selected.name.endsWith(".png", true)) selected else
                    File(selected.parentFile, "${selected.name}.png")
                val pixels = image.toPixelMap()
                val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) {
                    currentCoroutineContext().ensureActive()
                    for (x in 0 until image.width) buffered.setRGB(x, y, pixels[x, y].toArgb())
                }
                val targetPath = target.toPath().toAbsolutePath()
                val temporary = Files.createTempFile(targetPath.parent, ".kcode-export-", ".png")
                try {
                    check(ImageIO.write(buffered, "png", temporary.toFile()))
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        try {
                            Files.move(temporary, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(temporary, targetPath, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                } finally { Files.deleteIfExists(temporary) }
                ImageSaveResult.Saved(target.absolutePath)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ImageSaveResult.Failed(error.message)
        }
    }
}

private suspend fun chooseDesktopExportFile(fileName: String): File? =
    chooseDesktopExportFile(fileName) { null }

private suspend fun chooseDesktopExportFile(fileName: String, applicationWindow: () -> Frame?): File? {
    val dialog = withContext(NonCancellable + Dispatchers.Swing) {
        FileDialog(applicationWindow(), "Export conversation", FileDialog.SAVE).apply { file = fileName }
    }
    try {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { EventQueue.invokeLater { dialog.dispose() } }
            EventQueue.invokeLater {
                if (!continuation.isActive) return@invokeLater
                try {
                    dialog.isVisible = true
                    val directory = dialog.directory
                    val selected = dialog.file
                    if (continuation.isActive) continuation.resume(
                        if (directory == null || selected == null) null else File(directory, selected),
                    )
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    } finally {
        withContext(NonCancellable + Dispatchers.Swing) { dialog.dispose() }
    }
}
