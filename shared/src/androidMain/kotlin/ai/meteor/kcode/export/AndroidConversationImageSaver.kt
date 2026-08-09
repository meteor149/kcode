package ai.meteor.kcode.export

import android.content.ContentValues
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidConversationImageSaver(context: Context) : ConversationImageSaver {
    private val context = context
    private val resolver = context.contentResolver

    override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/kcode")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            try {
                resolver.openOutputStream(uri, "w").use { output ->
                    checkNotNull(output)
                    check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ImageSaveResult.Saved("Pictures/kcode/$fileName")
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }.getOrElse { ImageSaveResult.Failed(it.message) }
    }

    override suspend fun share(image: ImageBitmap, fileName: String): ImageSaveResult {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.cacheDir, "shared_images").apply { mkdirs() }
                directory.listFiles()?.forEach { it.delete() }
                val target = File(directory, fileName)
                target.outputStream().use { output ->
                    check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            }
        }
        val uri = prepared.getOrElse { return ImageSaveResult.Failed(it.message) }
        return runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null))
            ImageSaveResult.Shared
        }.getOrElse { ImageSaveResult.Failed(it.message) }
    }
}
