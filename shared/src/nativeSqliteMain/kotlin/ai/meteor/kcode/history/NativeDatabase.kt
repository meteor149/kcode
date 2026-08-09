package ai.meteor.kcode.history

import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

internal fun RoomDatabase.Builder<HistoryDatabase>.buildHistoryRepository(): ConversationHistoryRepository =
    RoomConversationHistoryRepository(
        setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build(),
    )
