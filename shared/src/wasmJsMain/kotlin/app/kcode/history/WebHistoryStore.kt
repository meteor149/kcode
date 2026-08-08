package app.kcode.history

import androidx.room3.Room
import app.kcode.history.worker.createSQLiteWasmWorker

fun createWebConversationHistoryRepository(): ConversationHistoryRepository =
    RoomConversationHistoryRepository(
        Room.databaseBuilder<HistoryDatabase>("kcode_history.db")
            .setDriver(createSQLiteWasmWorker())
            .build(),
    )
