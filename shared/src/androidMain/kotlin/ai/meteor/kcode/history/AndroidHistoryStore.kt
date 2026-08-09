package ai.meteor.kcode.history

import android.content.Context
import androidx.room3.Room

fun createAndroidConversationHistoryRepository(context: Context): ConversationHistoryRepository {
    val applicationContext = context.applicationContext
    val databaseFile = applicationContext.getDatabasePath("kcode_history.db")
    return Room.databaseBuilder<HistoryDatabase>(applicationContext, databaseFile.absolutePath)
        .buildHistoryRepository()
}
