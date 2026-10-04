package ai.meteor.kcode.plugin.history

import ai.meteor.kcode.plugin.api.HistoryRepositoryFactory
import ai.meteor.kcode.plugin.api.HistoryRepositoryResource
import android.content.Context
import androidx.room3.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun androidHistoryRepositoryFactory(context: Context): HistoryRepositoryFactory {
    val applicationContext = context.applicationContext
    return HistoryRepositoryFactory {
        val path = applicationContext.getDatabasePath("kcode_history.db").absolutePath
        val repository = Room.databaseBuilder<HistoryDatabase>(applicationContext, path).buildHistoryRepository()
        HistoryRepositoryResource(repository) { withContext(Dispatchers.IO) { repository.close() } }
    }
}
