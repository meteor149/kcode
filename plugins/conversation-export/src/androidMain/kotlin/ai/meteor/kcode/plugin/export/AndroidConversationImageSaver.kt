package ai.meteor.kcode.plugin.export

import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.ConversationImageSaverFactory
import ai.meteor.kcode.plugin.api.ConversationImageSaverResource
import ai.meteor.kcode.export.ConversationImageSaver
import ai.meteor.kcode.export.ImageSaveResult

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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun androidConversationImageSaverFactory(context: Context) = ConversationImageSaverFactory {
    val saver = AndroidConversationImageSaver { context }
    ConversationImageSaverResource(saver, saver::close)
}

fun androidConversationImageSaverFactory(inputs: AndroidPluginHostInputs) = ConversationImageSaverFactory {
    val saver = AndroidConversationImageSaver(inputs::activity)
    ConversationImageSaverResource(saver, saver::close)
}

internal class AndroidConversationImageSaver(contextProvider: () -> Context) : ConversationImageSaver {
    constructor(context: Context) : this({ context })

    private val contextSource = AtomicReference<(() -> Context)?>(contextProvider)
    @Volatile private var closed = false

    fun close() { closed = true; contextSource.set(null) }

    private fun requireOpen() = check(!closed) { "Image saver is closed" }
    private val applicationContext = contextProvider().applicationContext
    private val resolver = applicationContext.contentResolver

    override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult = withContext(Dispatchers.IO) {
        requireOpen()
        runCatching {
            validateFileName(fileName)
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
                currentCoroutineContext().ensureActive()
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1) { "Image publication failed" }
                ImageSaveResult.Saved("Pictures/kcode/$fileName")
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ImageSaveResult.Failed(error.message)
        }
    }

    override suspend fun share(image: ImageBitmap, fileName: String): ImageSaveResult {
        requireOpen()
        var target: File? = null
        var stagingDirectory: File? = null
        var handedOff = false
        return try {
            validateFileName(fileName)
            val uri = withContext(Dispatchers.IO) {
                val parent = File(applicationContext.cacheDir, "shared_images").apply { check(mkdirs() || isDirectory) }
                val directory = Files.createTempDirectory(parent.toPath(), "share-").toFile().also { stagingDirectory = it }
                val file = File(directory, fileName).also { target = it }
                file.outputStream().use { output ->
                    check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                FileProvider.getUriForFile(applicationContext, "${applicationContext.packageName}.fileprovider", file)
            }
            currentCoroutineContext().ensureActive()
            // OS handoff is the commit edge: retain the file once a receiver can open its URI.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(fileName, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                checkNotNull(contextSource.get()) { "Image saver is closed" }.invoke().startActivity(Intent.createChooser(send, null))
                handedOff = true
                ImageSaveResult.Shared
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ImageSaveResult.Failed(error.message)
        } finally {
            if (!handedOff) withContext(NonCancellable + Dispatchers.IO) {
                target?.delete()
                stagingDirectory?.delete()
            }
        }
    }

    private fun validateFileName(fileName: String) {
        require(fileName.isNotBlank() && fileName !in setOf(".", "..") &&
            '/' !in fileName && '\\' !in fileName && '\u0000' !in fileName) { "Invalid image file name" }
    }

}
