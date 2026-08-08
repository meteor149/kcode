@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.kcode.history

import androidx.room3.Room
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

fun createIosConversationHistoryRepository(): ConversationHistoryRepository {
    val directory = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )?.path,
    )
    return Room.databaseBuilder<HistoryDatabase>("$directory/kcode_history.db")
        .buildHistoryRepository()
}
