package ai.meteor.kcode.export

import androidx.compose.ui.graphics.ImageBitmap

sealed interface ImageSaveResult {
    data class Saved(val location: String) : ImageSaveResult
    data object Shared : ImageSaveResult
    data class Failed(val reason: String?) : ImageSaveResult
    data object Unsupported : ImageSaveResult
}

interface ConversationImageSaver {
    suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult
    suspend fun share(image: ImageBitmap, fileName: String): ImageSaveResult
}

object UnsupportedConversationImageSaver : ConversationImageSaver {
    override suspend fun save(image: ImageBitmap, fileName: String): ImageSaveResult = ImageSaveResult.Unsupported
    override suspend fun share(image: ImageBitmap, fileName: String): ImageSaveResult = ImageSaveResult.Unsupported
}
