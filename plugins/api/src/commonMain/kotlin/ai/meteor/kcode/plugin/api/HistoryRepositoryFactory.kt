package ai.meteor.kcode.plugin.api

import ai.meteor.kcode.history.ConversationHistoryRepository

/** Creates a fresh resource for each mount. Allocation must be bounded; close releases native storage. */
fun interface HistoryRepositoryFactory {
    suspend fun create(): HistoryRepositoryResource
}

class HistoryRepositoryResource(
    val repository: ConversationHistoryRepository,
    val close: suspend () -> Unit,
)
