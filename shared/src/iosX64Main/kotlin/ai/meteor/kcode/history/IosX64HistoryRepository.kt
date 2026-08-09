package ai.meteor.kcode.history

internal actual fun createIosConversationHistoryRepository(): ConversationHistoryRepository =
    TransientConversationHistoryRepository
