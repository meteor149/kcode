package ai.meteor.kcode.history

import androidx.room3.Room
import java.nio.file.Files
import java.nio.file.Path

fun createDesktopConversationHistoryRepository(): ConversationHistoryRepository {
    val databaseFile = Path.of(System.getProperty("user.home"), ".kcode", "history.db")
    Files.createDirectories(databaseFile.parent)
    return Room.databaseBuilder<HistoryDatabase>(databaseFile.toAbsolutePath().toString())
        .buildHistoryRepository()
}
