package ai.meteor.kcode.plugin.history

import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory
import ai.meteor.kcode.plugin.api.HistoryRepositoryResource
import androidx.room3.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

fun desktopHistoryRepositoryFactory(
    databaseFile: Path = Path.of(System.getProperty("user.home"), ".kcode", "history.db"),
): HistoryRepositoryFactory = HistoryRepositoryFactory {
    Files.createDirectories(databaseFile.toAbsolutePath().parent)
    val repository = Room.databaseBuilder<HistoryDatabase>(databaseFile.toAbsolutePath().toString()).buildHistoryRepository()
    HistoryRepositoryResource(repository) { withContext(Dispatchers.IO) { repository.close() } }
}
