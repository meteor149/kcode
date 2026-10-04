package ai.meteor.kcode.plugin.history



import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

internal fun RoomDatabase.Builder<HistoryDatabase>.buildHistoryRepository(): RoomConversationHistoryRepository {
    val database = setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
    return try {
        RoomConversationHistoryRepository(database)
    } catch (error: Throwable) {
        runCatching { database.close() }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }
}
