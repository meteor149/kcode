package app.kcode.export

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopConversationImageSaver : ConversationImageSaver {
    override suspend fun share(image: ImageBitmap, fileName: String): ImageSaveResult = ImageSaveResult.Unsupported

    override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult = withContext(Dispatchers.IO) {
        runCatching {
            val dialog = FileDialog(null as Frame?, "Export conversation", FileDialog.SAVE).apply {
                file = fileName
                isVisible = true
            }
            val directory = dialog.directory ?: return@runCatching ImageSaveResult.Failed(null)
            val selectedName = dialog.file ?: return@runCatching ImageSaveResult.Failed(null)
            val target = File(directory, if (selectedName.endsWith(".png", true)) selectedName else "$selectedName.png")
            val pixels = image.toPixelMap()
            val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    buffered.setRGB(x, y, pixels[x, y].toArgb())
                }
            }
            check(ImageIO.write(buffered, "png", target))
            ImageSaveResult.Saved(target.absolutePath)
        }.getOrElse { ImageSaveResult.Failed(it.message) }
    }
}
