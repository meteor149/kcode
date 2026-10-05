package ai.meteor.kcode.plugin.history

import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory
import ai.meteor.kcode.plugin.api.HistoryRepositoryResource
import android.content.Context
import androidx.room3.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun androidHistoryRepositoryFactory(context: Context, databasePath: String? = null): HistoryRepositoryFactory {
    val applicationContext = context.applicationContext
    return HistoryRepositoryFactory {
        val path = databasePath ?: applicationContext.getDatabasePath("kcode_history.db").absolutePath
        java.io.File(path).parentFile?.let { java.nio.file.Files.createDirectories(it.toPath()) }
        val repository = Room.databaseBuilder<HistoryDatabase>(applicationContext, path).buildHistoryRepository()
        HistoryRepositoryResource(repository) { withContext(Dispatchers.IO) { repository.close() } }
    }
}
